"""Verify generated scarecrow models, source PNG bytes, precise hat transform and final source recipe."""
from __future__ import annotations
import argparse,json,hashlib
from pathlib import Path
from zipfile import ZipFile
import yaml
from audit_upstream import read_blobs
from export_cookery_catalog import COMMIT


def check(runtime: Path,source: Path) -> dict:
    config=yaml.safe_load((runtime/'resources/cookery_kitchen/configuration/kitchen.yml').read_text(encoding='utf-8'))
    key='kaleidoscopecookery:scarecrow';furniture=config['furniture'][key];behavior=furniture['behavior'][0]
    assert behavior['type']==key and behavior['body_scale']==2 and behavior['hat_item']=='show:scarecrow_hat'
    assert furniture['settings']['hit_times']==1
    assert behavior['hat_roll']==-.2182
    for actual,expected in zip(map(float,behavior['hat_pivot'].split(',')),[.0074875,1.657675,-.0140625],strict=True):assert abs(actual-expected)<1e-8
    assert config['items'][key]['behavior']['rules']['ground']['rotation']=='eight'
    recipe=config['recipes']['kaleidoscopecookery:upstream/kaleidoscope_cookery/scarecrow']
    assert recipe['pattern']==[' H ','SPS',' # '] and recipe['result']=={'id':key,'count':1}
    assert recipe['ingredients']['S']=='minecraft:stick' and recipe['ingredients']['P']=='minecraft:pumpkin'
    source_paths=['src/main/resources/assets/kaleidoscope_cookery/textures/'+name+'.png' for name in ['entity/scarecrow','item/scarecrow']]
    originals=read_blobs(source,COMMIT,source_paths)
    pack=runtime/'generated/resource_pack.zip';parts={}
    with ZipFile(pack) as z:
        for part,count in [('body',11),('headless',10),('hat',2)]:
            ref=config['items']['show:scarecrow_'+part]['model']['path'];namespace,path=ref.split(':',1);name=f'assets/{namespace}/models/{path}.json'
            actual=json.loads(z.read(name));installed=json.loads((runtime/'resources/cookery_kitchen/resourcepack'/name).read_text(encoding='utf-8'))
            assert actual==installed and len(actual['elements'])==count
            for cube in actual['elements']:
                assert len(cube['faces'])==(2 if any(a==b for a,b in zip(cube['from'],cube['to'])) else 6)
                assert all(-16<=v<=32 for key in ['from','to'] for v in cube[key])
            parts[part]=actual
        body=parts['body']['elements'];headless=parts['headless']['elements']
        assert headless==body[:8]+body[9:]
        assert body[4]['from']==[1,16.383,7.5] and body[4]['to']==[15,17.383,8.5]
        for original in source_paths:
            generated=original.removeprefix('src/main/resources/')
            assert z.read(generated)==originals[original]
        assert 'elements' not in json.loads(z.read('assets/kaleidoscope_cookery/models/item/scarecrow.json'))
    return {'sourceCommit':COMMIT,'checkedDisplayModels':3,'sourceCubes':13,'checkedSourceTextures':2,
            'headToggleGeometry':True,'exactHatRollAndPivot':True,'sourceRecipe':True,'eightDirections':True,
            'resourcePackSha256':hashlib.sha256(pack.read_bytes()).hexdigest(),'failures':[],
            'clientRendering':'NOT_TESTED; checks generated files, source PNGs and explicit source-derived bounds/hat transform'}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('runtime',type=Path);p.add_argument('source',type=Path);p.add_argument('--output',type=Path)
    args=p.parse_args();raw=json.dumps(check(args.runtime,args.source),ensure_ascii=False,indent=2)+'\n'
    if args.output:args.output.write_text(raw,encoding='utf-8')
    print(raw)
