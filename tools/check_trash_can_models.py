"""Check installed/generated trash-can geometry, original texture bytes, recipe and overlay version."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile
import yaml
from audit_upstream import read_blobs
from export_cookery_catalog import COMMIT


def check(runtime: Path, source: Path, minecraft: str) -> dict:
    config=yaml.safe_load((runtime/'resources/cookery_kitchen/configuration/kitchen.yml').read_text(encoding='utf-8'))
    key='kaleidoscopecookery:trash_can'
    recipe=config['recipes']['kaleidoscopecookery:upstream/kaleidoscope_cookery/trash_can']
    assert recipe['pattern']==['III','ICI','III'] and recipe['result']=={'id':key,'count':1}
    assert recipe['ingredients']=={'C':'minecraft:composter','I':'minecraft:iron_ingot'}
    definition=config['furniture'][key]
    assert definition['behavior']==[{'type':'kaleidoscopecookery:trashcan'}]
    assert config['items'][key]['behavior']['furniture']==key
    source_paths=['src/main/resources/assets/kaleidoscope_cookery/textures/'+name+'.png'
                  for name in ['block/trash_can','gui/trash_can_overlay']]
    originals=read_blobs(source,COMMIT,source_paths)
    bounds={'trashcan_1':[([2,0,2],[14,12,14])],
            'trashcan_2':[([1,12,1],[15,15,15]),([11,15,7],[11,17,9]),([5,17,7],[11,17,9]),([5,15,7],[5,17,9])],
            'trashcan_3':[([3,7,3],[13,10,13])], 'trashcan_4':[([3,7,13.1],[13,10,13.1])]}
    pack=runtime/'generated/resource_pack.zip'
    with ZipFile(pack) as archive:
        for part,expected in bounds.items():
            ref=config['items']['show:'+part]['model']['path']
            namespace,path=ref.split(':',1)
            name=f'assets/{namespace}/models/{path}.json'
            model=json.loads(archive.read(name))
            installed=json.loads((runtime/'resources/cookery_kitchen/resourcepack'/name).read_text(encoding='utf-8'))
            assert model==installed,part+' generated model changed'
            assert [(e['from'],e['to']) for e in model['elements']]==expected,part+' bounds differ'
            for element in model['elements']:
                assert len(element['faces'])==(2 if any(a==b for a,b in zip(element['from'],element['to'])) else 6)
        for generated,original in [('block/trash_can',source_paths[0]),('misc/trash_can_overlay',source_paths[1])]:
            assert archive.read('assets/kaleidoscope_cookery/textures/'+generated+'.png')==originals[original]
        components=config['items']['kaleidoscopecookery:trashcan_helmet']['data']['components']
        if minecraft=='1.21.1':
            assert 'minecraft:equippable' not in components
        else:
            assert components['minecraft:equippable']['camera_overlay']=='kaleidoscope_cookery:misc/trash_can_overlay'
    return {'sourceCommit':COMMIT,'minecraft':minecraft,'checkedParts':4,'checkedCubes':7,'checkedSourceTextures':2,
            'sourceRecipe':True,'versionedOverlay':True,'resourcePackSha256':hashlib.sha256(pack.read_bytes()).hexdigest(),
            'failures':[],'clientRendering':'NOT_TESTED; compares generated files and original texture bytes'}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('runtime',type=Path);p.add_argument('source',type=Path);p.add_argument('minecraft');p.add_argument('--output',type=Path)
    args=p.parse_args();report=json.dumps(check(args.runtime,args.source,args.minecraft),ensure_ascii=False,indent=2)+'\n'
    if args.output:args.output.write_text(report,encoding='utf-8')
    print(report)
