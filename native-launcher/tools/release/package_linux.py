#!/usr/bin/env python3
"""Package a Linux jpackage image; dereference links for the safe bootstrap extractor."""
import argparse
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    image = args.image.resolve()
    if not (image / 'bin/GOSTEX').is_file():
        parser.error('Expected a native Linux GOSTEX jpackage app image')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    entries = 0
    expanded = 0
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        def visit(directory, relative, ancestors):
            nonlocal entries, expanded
            resolved = directory.resolve(strict=True)
            if resolved in ancestors or not resolved.is_relative_to(image):
                parser.error('Bundle contains a cyclic or external directory link')
            for source in sorted(directory.iterdir()):
                target = source.resolve(strict=True)
                if not target.is_relative_to(image):
                    parser.error('Bundle contains an external link')
                name = relative / source.name
                if target.is_dir():
                    visit(source, name, ancestors | {resolved})
                elif target.is_file():
                    entries += 1
                    expanded += target.stat().st_size
                    if entries > 20000 or expanded > 1024 ** 3:
                        parser.error('Bundle exceeds the bootstrap limits')
                    archive.write(target, 'GOSTEX/' + name.as_posix())
                else:
                    parser.error('Bundle contains an unsupported filesystem entry')
        visit(image, Path(), set())
    print(args.output)


if __name__ == '__main__':
    main()
