package net.kaleidoscope.music;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.Menu;
import net.kaleidoscope.series.SeriesPlugin;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import javax.sound.midi.MidiSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class MusicPlugin extends SeriesPlugin implements Listener {
    private static final long MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final String INSTRUMENT = "musicplus:handheld_instrument";
    private final Map<UUID, UUID> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Playback> playing = new ConcurrentHashMap<>();
    private record Playback(UUID token, ScheduledTask task) {}
    private ThreadPoolExecutor loader;
    private Path songs;

    @Override protected void start() {
        songs = getDataFolder().toPath().resolve("songs").toAbsolutePath().normalize();
        try { Files.createDirectories(songs); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Cannot create MIDI folder " + songs, exception); }
        loader = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().daemon(true).name("kaleidoscope-midi-loader").factory());
        getServer().getPluginManager().registerEvents(this, this);
        command("kmusic", (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("请在游戏内使用。"); return true; }
            if (!player.hasPermission("kaleidoscopemusic.play")) { player.sendMessage("没有演奏权限。"); return true; }
            attempt(player, () -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("stop")) { stopSong(player.getUniqueId()); return; }
                if (args.length == 1 && args[0].equalsIgnoreCase("kit") && player.hasPermission("kaleidoscopemusic.admin")) {
                    if (!InventoryTrade.give(player.getInventory(), Items.create(INSTRUMENT))) player.sendMessage("背包空间不足。");
                    return;
                }
                if (!holding(player)) { player.sendMessage("请先手持森罗乐器。"); return; }
                if (args.length == 2 && args[0].equalsIgnoreCase("play")) { loadSong(player, args[1]); return; }
                keyboard(player);
            });
            return true;
        });
    }

    private boolean holding(Player player) { return Items.id(player.getInventory().getItemInMainHand()).equals(INSTRUMENT); }

    @EventHandler public void useInstrument(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_AIR
                || event.useItemInHand() == Event.Result.DENY || !holding(player)
                || !player.hasPermission("kaleidoscopemusic.play")) return;
        event.setUseItemInHand(Event.Result.DENY);
        attempt(player, () -> keyboard(player));
    }

    private void keyboard(Player player) {
        Menu menu = new Menu(player, this, "森罗乐器 · 点击音符演奏", 3);
        for (int i = 0; i < 25; i++) {
            int key = 54 + i;
            menu.button(i, Items.icon(Material.NOTE_BLOCK, "音符 " + key), visitor -> {
                if (holding(visitor) && visitor.hasPermission("kaleidoscopemusic.play")) playNote(visitor, new MidiSong.Note(0, key, 100, 0, false));
            });
        }
        menu.button(26, Items.icon(Material.BARRIER, "停止播放"), visitor -> stopSong(visitor.getUniqueId()));
        menu.open(player);
    }

    private void loadSong(Player player, String filename) {
        if (filename.contains("/") || filename.contains("\\") || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mid")) {
            player.sendMessage("请填写 songs 目录中的 .mid 文件名。"); return;
        }
        stopSong(player.getUniqueId());
        UUID token = UUID.randomUUID();
        requests.put(player.getUniqueId(), token);
        UUID playerId = player.getUniqueId();
        try {
            loader.execute(() -> parseSong(player, playerId, filename, token));
        } catch (RejectedExecutionException exception) {
            requests.remove(playerId, token);
            player.sendMessage("正在读取的曲目过多，请稍后再试。");
        }
    }

    private void parseSong(Player player, UUID playerId, String filename, UUID token) {
        if (!isEnabled() || !token.equals(requests.get(playerId))) return;
        try {
            Path file = songs.resolve(filename).toRealPath();
            if (!file.startsWith(songs.toRealPath()) || Files.size(file) > MAX_FILE_BYTES) throw new IllegalArgumentException("MIDI 路径或文件大小无效。");
            MidiSong song;
            try (var stream = Files.newInputStream(file)) {
                byte[] bytes = stream.readNBytes((int) MAX_FILE_BYTES + 1);
                if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("MIDI 文件超过 4 MiB。");
                song = MidiSong.read(MidiSystem.getSequence(new java.io.ByteArrayInputStream(bytes)));
            }
            if (!isEnabled() || !token.equals(requests.get(playerId))) return;
            player.getScheduler().run(this, task -> {
                if (!token.equals(requests.get(playerId))) return;
                if (!holding(player)) { requests.remove(playerId, token); return; }
                startSong(player, song, token);
            }, () -> requests.remove(playerId, token));
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "无法读取 MIDI: " + filename, exception);
            if (isEnabled()) player.getScheduler().run(this, task -> {
                if (requests.remove(playerId, token)) player.sendMessage("无法播放该 MIDI，请检查文件格式、大小与管理员日志。");
            }, () -> requests.remove(playerId, token));
        }
    }

    private void startSong(Player player, MidiSong song, UUID token) {
        MusicTransport transport = new MusicTransport(song);
        long[] tick = {0};
        ScheduledTask task = player.getScheduler().runAtFixedRate(this, running -> {
            if (!isEnabled() || !holding(player) || !token.equals(requests.get(player.getUniqueId()))
                    || !player.hasPermission("kaleidoscopemusic.play")) {
                stopSong(player.getUniqueId(), token);
                running.cancel();
                return;
            }
            try {
                for (MidiSong.Note note : transport.tick(tick[0])) playNote(player, note);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(exception.getMessage());
                stopSong(player.getUniqueId(), token);
                return;
            }
            if (transport.finished(tick[0])) {
                stopSong(player.getUniqueId(), token);
                return;
            }
            tick[0]++;
        }, () -> stopSong(player.getUniqueId(), token), 1, 1);
        if (task == null) requests.remove(player.getUniqueId(), token);
        else playing.compute(player.getUniqueId(), (ignored, current) -> {
            // Retirement, stop, or disable may happen before the scheduler returns its handle.
            if (!isEnabled() || !token.equals(requests.get(player.getUniqueId()))) {
                task.cancel();
                return current;
            }
            if (current != null) current.task().cancel();
            return new Playback(token, task);
        });
    }

    private void playNote(Player player, MidiSong.Note note) {
        var location = player.getLocation();
        location.getWorld().playSound(location, "minecraft:" + MidiSong.sound(note), SoundCategory.RECORDS,
                Math.max(0.05f, note.velocity() / 127f), MidiSong.pitch(note.key()));
    }

    private void stopSong(UUID player) {
        requests.remove(player);
        Playback playback = playing.remove(player);
        if (playback != null) playback.task().cancel();
    }

    private void stopSong(UUID player, UUID token) {
        requests.remove(player, token);
        Playback playback = playing.get(player);
        if (playback != null && playback.token().equals(token) && playing.remove(player, playback)) {
            playback.task().cancel();
        }
    }

    @EventHandler public void left(PlayerQuitEvent event) { stopSong(event.getPlayer().getUniqueId()); }

    @Override protected void stop() {
        requests.clear();
        playing.values().forEach(playback -> playback.task().cancel());
        playing.clear();
        if (loader != null) loader.shutdownNow();
    }
}
