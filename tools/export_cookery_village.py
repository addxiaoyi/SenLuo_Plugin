"""Write pinned native village loot plus source structure manifests to a new ZIP."""
import argparse
from pathlib import Path
import sys
from zipfile import BadZipFile, ZipFile, ZipInfo, ZIP_DEFLATED

from audit_upstream import AuditError
from cookery_village import export, json_bytes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runtime-manifest', type=Path, help='Optional new plugin resource file; never overwrite')
    parser.add_argument('--check', action='store_true', help='Compare existing ZIP/resource exactly, without writing')
    args = parser.parse_args()
    try:
        files, manifest = export(args.source)
        if args.check:
            with ZipFile(args.output) as archive:
                if archive.namelist() != sorted(files) or any(archive.read(name) != raw for name, raw in files.items()):
                    raise AuditError('village ZIP differs from pinned export')
            if args.runtime_manifest and args.runtime_manifest.read_bytes() != json_bytes(manifest):
                raise AuditError('village plugin resource differs from pinned export')
            print('Village source/native loot export matches exactly; runtime NOT_TESTED')
            return 0
        if args.output.exists() or args.runtime_manifest and args.runtime_manifest.exists():
            raise AuditError('refusing to overwrite existing village export')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(args.output, 'x', compression=ZIP_DEFLATED) as archive:
            for path, raw in sorted(files.items()):
                info = ZipInfo(path, (1980, 1, 1, 0, 0, 0)); info.compress_type = ZIP_DEFLATED
                archive.writestr(info, raw)
        if args.runtime_manifest:
            args.runtime_manifest.parent.mkdir(parents=True, exist_ok=True)
            with args.runtime_manifest.open('xb') as stream:
                stream.write(json_bytes(manifest))
    except (AuditError, OSError, BadZipFile) as failure:
        print(str(failure), file=sys.stderr)
        return 1
    print(f'Exported {len(manifest["structures"])} source structures, 2 native chest tables, {len(manifest["recipes"])} recipe choices; natural generation NOT_IMPLEMENTED')
    return 0


if __name__ == '__main__':
    sys.exit(main())
