"""Pinned scarecrow equipment furniture and Java mesh; its tilted hat stays a separate display."""
from __future__ import annotations
import hashlib
import re
from pathlib import Path
from audit_upstream import AuditError,read_blobs,uncomment_java
from export_cookery_catalog import COMMIT,arguments,chain,number
from cookery_millstone import Cube,Part
from cookery_trash_can import mesh_cube
from cookery_kitchen_displays import display_item

ART='kaleidoscope_cookery';PLUGIN='kaleidoscopecookery'
JAVA='src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/'
MODEL_SOURCE=JAVA+'client/model/ScarecrowModel.java'
SOURCES=(MODEL_SOURCE,JAVA+'entity/ScarecrowEntity.java',JAVA+'item/ScarecrowItem.java',
         JAVA+'client/render/entity/ScarecrowRender.java',JAVA+'client/render/entity/layer/ScarecrowHandLayer.java')


def parse_mesh(source: str) -> tuple[dict,dict]:
    match=re.search(r'public static LayerDefinition createBodyLayer\(\)\s*\{([^{}]+)\}',uncomment_java(source))
    if not match:raise AuditError('scarecrow: unknown model factory')
    statements=[s.strip() for s in match[1].split(';') if s.strip()]
    if statements[:2]!=['MeshDefinition meshdefinition = new MeshDefinition()','PartDefinition partdefinition = meshdefinition.getRoot()'] or statements[-1]!='return LayerDefinition.create(meshdefinition, 128, 128)':
        raise AuditError('scarecrow: unknown root/texture dimensions')
    parts,mirrors={},{}
    for statement in statements[2:-1]:
        m=re.fullmatch(r'PartDefinition (\w+) = (\w+)\.addOrReplaceChild\((.*)\)',statement,re.S)
        if not m:raise AuditError('scarecrow: unknown part declaration')
        name,parent,params=m.groups();args=arguments(params);prefix='CubeListBuilder.create()'
        if len(args)!=3 or args[0]!='"'+name+'"' or not args[1].startswith(prefix) or name in parts:
            raise AuditError('scarecrow: unknown builder')
        cubes=[];flags=[];mirror=False;uv=None
        for method,values in chain(args[1][len(prefix):]):
            if method=='texOffs' and len(values)==2:uv=tuple(number(v) for v in values)
            elif method=='mirror' and values in ([],['false'],['true']):mirror=values!=['false']
            elif method=='addBox' and len(values)==7 and uv is not None and values[6]=='new CubeDeformation(0.0F)':
                size=tuple(number(v) for v in values[3:6])
                if any(v<0 for v in size) or sum(v>0 for v in size)<2:raise AuditError('scarecrow: invalid cube')
                cubes.append(Cube(uv,tuple(number(v) for v in values[:3]),size,0));flags.append(mirror)
            else:raise AuditError('scarecrow: unsupported builder call '+method)
        pose=re.fullmatch(r'PartPose\.(offset|offsetAndRotation)\(([^()]+)\)',args[2])
        if not pose:raise AuditError('scarecrow: unknown pose')
        values=tuple(number(v) for v in arguments(pose[2]))
        if len(values)!=(3 if pose[1]=='offset' else 6):raise AuditError('scarecrow: invalid pose')
        parts[name]=Part(parent,values[:3],values[3:] if len(values)==6 else (0,0,0),tuple(cubes));mirrors[name]=flags
    expected={'group':('partdefinition',8),'head':('group',1),'hat':('head',0),
              'headwear_r1':('hat',2),'rightArm':('group',1),'leftArm':('group',1)}
    if {name:(p.parent,len(p.cubes)) for name,p in parts.items()}!=expected:raise AuditError('scarecrow: unknown hierarchy')
    for name,p in parts.items():
        if p.rotation!=((0,0,.2182) if name=='headwear_r1' else (0,0,0)):raise AuditError('scarecrow: unknown rotation')
    return parts,mirrors


def models(parts: dict,mirrors: dict) -> tuple[dict,list[float]]:
    # The arbitrary source hat tilt cannot be represented by old block JSON angles.
    # Bake its unrotated geometry, then apply the exact transform on its display.
    baked={name:Part(p.parent,p.offset,(0,0,0),p.cubes) for name,p in parts.items()}
    groups={'scarecrow_body':('group','head','rightArm','leftArm'),
            'scarecrow_headless':('group','rightArm','leftArm'),'scarecrow_hat':('headwear_r1',)}
    output={}
    for name,members in groups.items():
        elements=[]
        for member in members:
            for cube,mirror in zip(parts[member].cubes,mirrors[member],strict=True):
                element=mesh_cube(cube,member,baked,mirrored=mirror,texture_size=128,render_y=24.016)
                # Half-size JSON stays within old vanilla [-16,32] bounds; display scale=2 restores it.
                for key in ('from','to'):element[key]=[round(8+(v-8)*.5,6) for v in element[key]]
                if not all(-16<=v<=32 for key in ('from','to') for v in element[key]):raise AuditError('scarecrow: model bounds')
                elements.append(element)
        output[name]={'textures':{'body':ART+':entity/scarecrow','particle':ART+':entity/scarecrow'},'elements':elements}
    pivot=[0.,0.,0.];current='headwear_r1'
    while current!='partdefinition':
        p=parts[current];pivot=[a+b for a,b in zip(pivot,p.offset,strict=True)];current=p.parent
    return output,[pivot[0]/16,(24.016-pivot[1]-8)/16,-pivot[2]/16]


def scarecrow(source: Path,assets: dict,items: dict,definitions: dict) -> tuple[dict,dict]:
    files=read_blobs(source,COMMIT,list(SOURCES));parts,mirrors=parse_mesh(files[MODEL_SOURCE].decode('utf-8'))
    geometry,pivot=models(parts,mirrors)
    for name,model in geometry.items():display_item(assets,items,'show:'+name,model)
    key=PLUGIN+':scarecrow'
    items[key]={'material':'paper','data':{'item_name':'<!i>稻草人'},'model':definitions[ART+':item/scarecrow'],
                'behavior':{'type':'furniture_item','furniture':key,'rules':{'ground':{'rotation':'eight','alignment':'center'}}}}
    # One source hand band shared by both hand endpoints. Source typo 27/17 is intentional.
    hand_low=17/16;head_low=27/17
    head_hit={'type':'interaction','position':f'0,{head_low},0','width':.5,'height':2.375-head_low,'interactive':True,'blocks_building':True}
    hand_hit={'type':'interaction','position':f'0,{hand_low},0','width':.5,'height':head_low-hand_low,'interactive':True,'blocks_building':True}
    behavior={'type':key,'body_scale':2,'hat_item':'show:scarecrow_hat','hat_pivot':','.join(str(v) for v in pivot),'hat_roll':-.2182,
              'slots':{'head':{'hitbox':head_hit},'main_hand':{'hitbox':hand_hit},
                       'off_hand':{'hitbox':{'type':'interaction','position':'0,0,0','width':.01,'height':.01,'interactive':False,'blocks_building':False}}}}
    furniture={'settings':{'item':key,'hit_times':1,'sounds':{'place':{'id':'minecraft:entity.armor_stand.place','volume':.75,'pitch':.8},
                 'break':{'id':'minecraft:entity.armor_stand.break'},'hit':{'id':'minecraft:entity.armor_stand.hit','volume':.3}}},
        'behavior':[behavior],'variants':{'ground':{'elements':[],'hitboxes':[{'type':'interaction','position':'0,0,0','width':.5,'height':hand_low,'interactive':False,'blocks_building':True}]}},
        'loot':{'pools':[{'rolls':1,'entries':[{'type':'item','item':key}]}]}}
    report={'sourceCommit':COMMIT,'sourceFiles':{p:hashlib.sha256(raw).hexdigest() for p,raw in files.items()},
            'sourceCubes':13,'headlessCubes':10,'bodyWithHeadCubes':11,'hatCubes':2,'displayScale':2,'hatPivot':pivot,'hatRollRadians':-.2182,
            'runtime':'NOT_TESTED','clientVisual':'NOT_TESTED',
            'differences':['CE furniture remains anchored; source gravity/minecart riding not yet implemented.',
                           'Source explosion/equipment-only and named-item drops need separate lifecycle integration.',
                           'Parrot capture/release persists differently; source entity NBT shoulder is not yet ported.',
                           'Equipment display transforms and client head/hat alignment still require visual acceptance.',
                           'Ground furniture placement is not yet the full source placement collision/entity/event contract.']}
    return {key:furniture},report
