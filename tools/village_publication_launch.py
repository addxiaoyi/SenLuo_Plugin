"""Prepare/check an exact future village-smoke launch record; never start a process.

The caller must still obtain the user's server window and perform the normal runner
checks before any launch. This record freezes selected runtime files, the fixed
command, cwd and a deliberately small child environment. It does not freeze the
operating system, native system libraries, network, user registry or runtime state.
"""
from __future__ import annotations

import argparse
from collections.abc import Mapping
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys

import village_publication_candidate as candidate_inputs
from village_publication_report import MAX_REPORT_BYTES, read_json, require, _time, _same

SCHEMA = 'village-publication-launch-v1'
FIXED_JVM_ARGS = (
    '-Xms512M', '-Xmx2200M', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
)
FIXED_SERVER_ARGS = ('-jar', 'server.jar', 'nogui')
# These are not passed through even when empty. No option-variable parsing or
# @argfile expansion is attempted: the supported launch has one fixed argv.
BLOCKED = frozenset((
    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH',
    'JDK_ALTERNATE_VM', 'IBM_JAVA_OPTIONS', 'OPENJ9_JAVA_OPTIONS',
    'JAVA_OPTS', 'JAVA_OPTIONS', 'JVM_OPTS', 'JVM_OPTIONS',
    'LD_PRELOAD', 'LD_LIBRARY_PATH', 'LD_AUDIT', 'LD_DEBUG', 'LD_PROFILE',
    'LD_ASSUME_KERNEL', 'GLIBC_TUNABLES', '__COMPAT_LAYER',
))
JAVA_PREFIXES = ('JAVA_', 'JDK_', 'JRE_', 'JVM_', '_JAVA_')
PROFILE_PATHS = ('HOME', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'TEMP', 'TMP', 'TMPDIR')
RECORD_KEYS = frozenset((
    'schema', 'candidateSha256', 'runId', 'platform', 'preparedAt', 'hostPlatform',
    'executable', 'argv', 'cwd', 'environment', 'processOptions', 'policy', 'startup',
))
POLICY = {
    'jvmArguments': 'FIXED_EXISTING_ACCEPTANCE_BASELINE',
    'heap': 'UNCHANGED_XMS512M_XMX2200M',
    'watchdog': 'UNCHANGED_NO_OVERRIDE',
    'inheritedEnvironment': 'ALLOWLIST_ONLY_NO_SECRET_VALUES_CAPTURED',
    'optionVariables': 'REJECT_ON_PRESENCE_INCLUDING_EMPTY',
    'argumentFiles': 'UNSUPPORTED',
    'systemInputs': 'OS_NATIVE_LIBRARIES_NETWORK_USER_REGISTRY_NOT_FROZEN',
    'launchAuthorization': 'NOT_PROVIDED_BY_THIS_RECORD',
}


def candidate_digest(candidate: dict) -> str:
    """Bind the complete independently frozen candidate, not just its run label."""
    require(type(candidate) is dict, 'A complete frozen candidate object is required')
    raw = json.dumps(candidate, sort_keys=True, separators=(',', ':'), ensure_ascii=False,
                     allow_nan=False).encode('utf-8')
    require(0 < len(raw) <= MAX_REPORT_BYTES, 'Candidate exceeds the launch binding bound')
    return hashlib.sha256(raw).hexdigest()


def _environment_names(inherited: Mapping[str, str]) -> dict[str, str]:
    require(isinstance(inherited, Mapping) and len(inherited) <= 4_096,
            'Inherited environment is not a bounded mapping')
    names = {}
    for name in inherited:
        require(type(name) is str and 0 < len(name) <= 256 and '\0' not in name and '=' not in name,
                'Unsupported inherited environment variable name')
        canonical = name.upper()
        require(canonical not in names, 'Ambiguous case-variant inherited environment names')
        if canonical in BLOCKED:
            # The value is never inspected, copied, interpolated or printed.
            raise ValueError('Unsupported inherited launcher override: ' + canonical)
        require(not canonical.startswith(('LD_', 'DYLD_', 'LDR_', 'LIBPATH', 'JLI_')),
                'Unsupported inherited native-loader override')
        require(not canonical.startswith(JAVA_PREFIXES) or canonical == 'JAVA_HOME',
                'Unknown inherited Java launcher override')
        names[canonical] = name
    return names


def _directory(value: object, name: str) -> str:
    require(type(value) is str and 0 < len(value) <= 32_768 and '\0' not in value,
            'Invalid directory-valued launch environment field: ' + name)
    path = Path(value)
    require(path.is_absolute(), 'Launch environment directory must be absolute: ' + name)
    path = candidate_inputs.unlinked_path(path)
    require(candidate_inputs.fs_path(path).is_dir(), 'Launch environment directory is unavailable: ' + name)
    return str(path)


def _controlled_path(components: tuple[str, ...], separator: str) -> str:
    require(all(component and separator not in component for component in components),
            'A controlled PATH directory contains its platform path separator')
    return separator.join(components)


def sanitized_environment(java: Path, inherited: Mapping[str, str], *, host_platform: str | None = None) -> dict[str, str]:
    """Preserve only validated directory fields; discard every other inherited value.

    JAVA_HOME and PATH are derived from the selected executable and validated system
    directory. HOME/profile/temp variables retain their existing directory meaning;
    they are never redirected to the candidate and no directory is created here.
    """
    platform = os.name if host_platform is None else host_platform
    require(platform in ('nt', 'posix'), 'Unsupported host launch platform')
    names = _environment_names(inherited)
    java = candidate_inputs.unlinked_path(java)
    require(candidate_inputs.fs_path(java).is_file(), 'Selected Java executable is unavailable')
    result = {'JAVA_HOME': str(java.parent.parent)}
    for name in PROFILE_PATHS:
        if name in names and (platform == 'nt' or names[name] == name):
            result[name] = _directory(inherited[names[name]], name)
    if platform == 'nt':
        require('SYSTEMROOT' in names, 'Windows launch requires its inherited system directory')
        root = _directory(inherited[names['SYSTEMROOT']], 'SYSTEMROOT')
        system32 = _directory(str(Path(root) / 'System32'), 'SYSTEMROOT/System32')
        if 'WINDIR' in names:
            require(_directory(inherited[names['WINDIR']], 'WINDIR') == root,
                    'Inherited Windows system directory fields disagree')
        result['SystemRoot'] = result['WINDIR'] = root
        result['PATH'] = _controlled_path((str(java.parent), system32, root), ';')
    else:
        # These operating-system tool directories are explicit dependencies, not
        # content-addressed runtime files. The selected Java itself is absolute.
        result['PATH'] = _controlled_path((str(java.parent), '/usr/bin', '/bin'), ':')
        result['LANG'] = result['LC_ALL'] = 'C.UTF-8'
    return result


def _spec(candidate: dict, inherited: Mapping[str, str]) -> dict:
    # Environment veto precedes file scanning as well as any possible future
    # process. This module imports no process-launching API.
    _environment_names(inherited)
    candidate_inputs.verify(candidate, before_start=True)
    executable = candidate_inputs.unlinked_path(Path(candidate['javaExecutable']))
    instance = candidate_inputs.unlinked_path(Path(candidate['instance']))
    require(executable.name.lower() in ('java', 'java.exe'), 'Use the actual direct Java launcher, not a wrapper')
    argv = [str(executable), *FIXED_JVM_ARGS, *FIXED_SERVER_ARGS]
    require(not any(value.startswith('@') for value in argv), 'Launch argument files are unsupported')
    return {
        'candidateSha256': candidate_digest(candidate), 'runId': candidate['runId'], 'platform': candidate['platform'],
        'hostPlatform': os.name, 'executable': str(executable), 'argv': argv, 'cwd': str(instance),
        'environment': sanitized_environment(executable, inherited),
        'processOptions': {'shell': False, 'stdin': 'PIPE', 'stdout': 'OWNED_CONSOLE_LOG', 'stderr': 'STDOUT',
                           'creationFlags': 'CREATE_NO_WINDOW' if os.name == 'nt' else 'NONE',
                           'startNewSession': os.name != 'nt'},
        'policy': dict(POLICY), 'startup': 'NOT_RUN',
    }


def prepare(candidate: dict, inherited: Mapping[str, str] | None = None) -> dict:
    """Return a concrete future launch record. No EULA, process or server state is changed."""
    inherited = os.environ if inherited is None else inherited
    spec = _spec(candidate, inherited)
    prepared_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
    require(_time(prepared_at) >= _time(candidate['frozenAt']), 'Launch preparation predates the frozen candidate')
    record = {'schema': SCHEMA, 'preparedAt': prepared_at, **spec}
    require(len(json.dumps(record, indent=2).encode('utf-8')) <= MAX_REPORT_BYTES, 'Launch record exceeds its byte bound')
    return record


def validate(record: dict, candidate: dict, inherited: Mapping[str, str] | None = None) -> None:
    require(type(record) is dict and set(record) == RECORD_KEYS and record.get('schema') == SCHEMA,
            'Unknown or incomplete launch record')
    _time(record['preparedAt'])
    require(_time(record['preparedAt']) >= _time(candidate['frozenAt']), 'Launch preparation predates the frozen candidate')
    inherited = os.environ if inherited is None else inherited
    expected = {'schema': SCHEMA, 'preparedAt': record['preparedAt'], **_spec(candidate, inherited)}
    require(_same(record, expected), 'Frozen launch command, directory, environment or candidate binding drifted')


def write_new_record(record: dict, output: Path, candidate: dict) -> None:
    output = candidate_inputs.unlinked_path(output)
    instance = candidate_inputs.unlinked_path(Path(candidate['instance']))
    require(output.is_relative_to(candidate_inputs.ACCEPTANCE.resolve()) and not output.is_relative_to(instance)
            and output.parent.is_dir(), 'Store the new launch record outside the instance under existing build/acceptance directories')
    raw = (json.dumps(record, indent=2, ensure_ascii=False, allow_nan=False) + '\n').encode('utf-8')
    require(len(raw) <= MAX_REPORT_BYTES, 'Launch record exceeds its byte bound')
    with candidate_inputs.fs_path(output).open('xb') as stream:
        stream.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    prepare_parser = commands.add_parser('prepare')
    prepare_parser.add_argument('--candidate', type=Path, required=True)
    prepare_parser.add_argument('--output', type=Path, required=True)
    check = commands.add_parser('check')
    check.add_argument('--candidate', type=Path, required=True)
    check.add_argument('--record', type=Path, required=True)
    args = parser.parse_args()
    try:
        candidate = read_json(args.candidate)
        if args.action == 'prepare':
            record = prepare(candidate)
            write_new_record(record, args.output, candidate)
            print(json.dumps({'runId': record['runId'], 'startup': 'NOT_RUN', 'commandAndEnvironment': 'PREPARED_ONLY'}))
        else:
            validate(read_json(args.record), candidate)
            print('Frozen launch inputs match; no process was started.')
        return 0
    except (OSError, ValueError, KeyError, TypeError, RecursionError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
