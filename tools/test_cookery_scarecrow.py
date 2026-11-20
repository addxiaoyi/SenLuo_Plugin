import copy
import json
import unittest
from pathlib import Path
from audit_upstream import AuditError,read_blobs
from export_cookery_catalog import COMMIT
from cookery_scarecrow import MODEL_SOURCE,parse_mesh,models

SOURCE=Path(__file__).resolve().parents[2]/'.references/KaleidoscopeCookery'


class ScarecrowMeshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=read_blobs(SOURCE,COMMIT,[MODEL_SOURCE])[MODEL_SOURCE].decode('utf-8')
        cls.parts,cls.mirrors=parse_mesh(cls.source)

    def test_all_source_cubes_and_plane_faces_survive_split_head_and_hat(self):
        output,pivot=models(self.parts,self.mirrors)
        self.assertEqual([11,10,2],[len(output[name]['elements']) for name in ['scarecrow_body','scarecrow_headless','scarecrow_hat']])
        for model in output.values():
            for cube in model['elements']:
                planes=sum(a==b for a,b in zip(cube['from'],cube['to']))
                self.assertEqual(2 if planes else 6,len(cube['faces']))
                self.assertTrue(all(-16<=v<=32 for key in ['from','to'] for v in cube[key]))
                self.assertTrue(all(0<=v<=16 for face in cube['faces'].values() for v in face['uv']))
        body=output['scarecrow_body']['elements']
        self.assertEqual([1,16.383,7.5],body[4]['from'])
        self.assertEqual([15,17.383,8.5],body[4]['to'])
        self.assertAlmostEqual(.0074875,pivot[0]);self.assertAlmostEqual(1.657675,pivot[1]);self.assertAlmostEqual(-.0140625,pivot[2])

    def test_mirrored_source_parts_have_reversed_face_uv_not_moved_geometry(self):
        output,_=models(self.parts,self.mirrors)
        plain=copy.deepcopy(self.mirrors);plain['rightArm']=[False]
        nonmirror,_=models(self.parts,plain)
        actual=output['scarecrow_headless']['elements'][8];before=nonmirror['scarecrow_headless']['elements'][8]
        self.assertEqual(actual['from'],before['from']);self.assertEqual(actual['to'],before['to'])
        self.assertNotEqual(actual['faces'],before['faces'])
        self.assertEqual(2,sum(sum(flags) for flags in self.mirrors.values()))

    def test_source_rotation_and_mirror_syntax_fail_closed(self):
        for old,new in [('0.2182F','0.3F'),('.mirror(false)','.mirror(unknown)'),('128, 128','64, 64')]:
            with self.subTest(old=old),self.assertRaises(AuditError):parse_mesh(self.source.replace(old,new))


if __name__=='__main__':unittest.main()
