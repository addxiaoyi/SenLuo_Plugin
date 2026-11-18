import json
from pathlib import Path
import unittest

from audit_upstream import AuditError
from cookery_trash_can import MODEL_SOURCE, parse_mesh, mesh_cube
from export_trash_can_animations import export, OUTPUT, parse as parse_animations

SOURCE = Path(__file__).resolve().parents[2] / '.references/KaleidoscopeCookery'


class TrashCanModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (SOURCE / MODEL_SOURCE).read_text(encoding='utf-8')
        cls.parts = parse_mesh(cls.source)

    def test_java_geometry_preserves_body_lid_eye_and_rotated_planar_handle(self):
        expected = {
            'bone': [([2,0,2], [14,12,14], 6)],
            'bone2': [([1,12,1], [15,15,15], 6)],
            'cube_r1': [([11,15,7], [11,17,9], 2), ([5,17,7], [11,17,9], 2), ([5,15,7], [5,17,9], 2)],
            'eye': [([3,7,3], [13,10,13], 6)],
            'bone3': [([3,7,13.1], [13,10,13.1], 2)],
        }
        for name, bounds in expected.items():
            actual = [mesh_cube(cube, name, self.parts) for cube in self.parts[name].cubes]
            self.assertEqual(bounds, [(cube['from'],cube['to'],len(cube['faces'])) for cube in actual])
            for cube in actual:
                for face in cube['faces'].values():
                    self.assertTrue(all(0 <= n <= 16 for n in face['uv']))
        body = mesh_cube(self.parts['bone'].cubes[0], 'bone', self.parts)
        self.assertEqual([7,13,10,16], body['faces']['south']['uv'])
        self.assertEqual([13,13,16,16], body['faces']['north']['uv'])
        handle = mesh_cube(self.parts['cube_r1'].cubes[1], 'cube_r1', self.parts)
        self.assertEqual(270, handle['faces']['up']['rotation'])
        self.assertEqual(90, handle['faces']['down']['rotation'])

    def test_unknown_source_dimensions_rotation_and_hierarchy_fail_closed(self):
        for before,after in [('64, 64','128, 128'), ('-1.5708F','-0.7854F'),
                             ('eye.addOrReplaceChild("bone3"','root.addOrReplaceChild("bone3"')]:
            with self.subTest(before=before), self.assertRaises(AuditError):
                parse_mesh(self.source.replace(before, after))

    def test_bundled_animation_channels_match_every_pinned_java_frame(self):
        raw=export(SOURCE)
        self.assertEqual(raw,OUTPUT.read_bytes())
        animations=json.loads(raw)['animations']
        self.assertEqual(8,len(animations['PUT']['channels'][0]['frames']))
        self.assertEqual(.4573,animations['PLAYER1']['channels'][3]['frames'][1]['time'])
        self.assertEqual('CATMULLROM',animations['ENTER']['channels'][0]['frames'][0]['interpolation'])
        self.assertEqual(5,len(animations))


if __name__ == '__main__': unittest.main()
