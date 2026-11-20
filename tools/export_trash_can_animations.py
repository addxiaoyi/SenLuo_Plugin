"""Extract the pinned source animation channels without rounding their timestamps."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
from audit_upstream import AuditError, read_blobs, uncomment_java
from export_cookery_catalog import COMMIT, arguments, chain, number

SOURCE_PATH = 'src/main/java/com/github/ysbbbbbb/kaleidoscopecookery/client/animation/TrashCanAnimation.java'
OUTPUT = Path(__file__).resolve().parents[1] / 'src/main/resources/trash-can-animations.json'


def parse(source: str) -> dict:
    text = uncomment_java(source)
    matches = re.findall(r'public static final AnimationDefinition (\w+) = AnimationDefinition\.Builder\.withLength\(([^()]+)\)(.*?);', text, re.S)
    if {name for name, _, _ in matches} != {'PUT','WITHDRAW','PLAYER1','PLAYER2','ENTER'} or len(matches)!=5:
        raise AuditError('Unknown trash can animation declarations')
    result={}
    for name,length,body in matches:
        channels=[]
        calls=chain(body.strip())
        if calls[-1]!=('build',[]):raise AuditError('Unknown animation builder ending')
        for method,values in calls[:-1]:
            if method!='addAnimation' or len(values)!=2 or values[0] not in ('"bone"','"bone2"','"eye"','"bone3"'):
                raise AuditError('Unknown trash can animation channel')
            channel=re.fullmatch(r'new AnimationChannel\(AnimationChannel.Targets\.(ROTATION|POSITION),\s*(.*)\)',values[1],re.S)
            if not channel:raise AuditError('Unknown trash can animation target')
            frames=[]
            for expression in arguments(channel[2]):
                frame=re.fullmatch(r'new Keyframe\(([^,]+),\s*KeyframeAnimations\.(degreeVec|posVec)\(([^()]+)\),\s*AnimationChannel.Interpolations\.(LINEAR|CATMULLROM)\)',expression,re.S)
                if not frame or frame[2]!=('degreeVec' if channel[1]=='ROTATION' else 'posVec'):
                    raise AuditError('Unknown trash can keyframe')
                vector=[number(v) for v in arguments(frame[3])]
                if len(vector)!=3:raise AuditError('Invalid animation vector')
                frames.append({'time':number(frame[1]),'value':vector,'interpolation':frame[4]})
            if not frames or any(a['time']>=b['time'] for a,b in zip(frames,frames[1:])):
                raise AuditError('Unordered animation frames')
            channels.append({'part':json.loads(values[0]),'target':channel[1],'frames':frames})
        result[name]={'length':number(length),'channels':channels}
    return result


def export(source: Path) -> bytes:
    raw=read_blobs(source,COMMIT,[SOURCE_PATH])[SOURCE_PATH]
    doc={'sourceCommit':COMMIT,'sourcePath':SOURCE_PATH,'sourceSha256':hashlib.sha256(raw).hexdigest(),
         'coordinateSpace':'Source degreeVec/posVec inputs before the renderer transform',
         'animations':parse(raw.decode('utf-8'))}
    return (json.dumps(doc,ensure_ascii=False,indent=2)+'\n').encode('utf-8')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--check',action='store_true')
    args=p.parse_args();raw=export(args.source)
    if args.check:
        if OUTPUT.read_bytes()!=raw:raise AuditError('Animation resource differs from pinned Java source')
    else:OUTPUT.write_bytes(raw)
    print('trash can animation source hash and all five tracks verified')
