#!/usr/bin/env python3
# GENERATED FILE — DO NOT EDIT. This file is overwritten on every protolake build.
"""PyPI publisher for Proto Lake bundles"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# Add parent directory to path for utilities
sys.path.insert(0, str(Path(__file__).parent))
from publisher_utils_generated import ensure_directory_exists, calculate_checksum


def read_bundle_version(bundle_yaml_path):
    """Read the top-level `version:` from a bundle.yaml.

    Minimal line parser on purpose — this tool runs under bazel py runtimes
    with stdlib only, so no yaml library. Fails loudly when the file is
    unreadable, no version line is found, or the version is malformed.
    """
    version = None
    try:
        with open(bundle_yaml_path, encoding='utf-8') as f:
            for line in f:
                match = re.match(r"^version:\s*['\"]?([^'\"\s]+)", line)
                if match:
                    version = match.group(1)
                    break
    except OSError as e:
        print(f"Error: cannot read bundle.yaml at {bundle_yaml_path}: {e}",
              file=sys.stderr)
        sys.exit(1)
    if version is None:
        print(f"Error: no top-level 'version:' line found in {bundle_yaml_path}",
              file=sys.stderr)
        sys.exit(1)
    if not re.fullmatch(r'[0-9A-Za-z.+~-]+', version):
        print(f"Error: malformed version {version!r} in {bundle_yaml_path}",
              file=sys.stderr)
        sys.exit(1)
    return version


def normalize_project_name(package_name):
    """Normalize a project name per PEP 503: lowercase, runs of `-_.` to `-`.

    This is the form pip requests from a simple index, so the per-project
    directory must use it.
    """
    return re.sub(r'[-_.]+', '-', package_name).lower()


# PEP 440's version grammar, as the `packaging` library spells it (stdlib only
# here, so no import of it). Used to compare the bundle's version with the
# canonical form setuptools stamped into the wheel. The components match
# ASCII only — `(?a:...)` — so Unicode case folding can't admit a spelling
# packaging rejects (the long s in `1.0poſt1`, the Kelvin sign in `1.0+K`).
_PEP440_VERSION = re.compile(r"""
    ^\s*
    (?a:
        v?
        (?:(?P<epoch>[0-9]+)!)?
        (?P<release>[0-9]+(?:\.[0-9]+)*)
        (?P<pre>[-_.]?(?P<pre_l>alpha|a|beta|b|preview|pre|c|rc)[-_.]?(?P<pre_n>[0-9]+)?)?
        (?P<post>(?:-(?P<post_n1>[0-9]+))|(?:[-_.]?(?P<post_l>post|rev|r)[-_.]?(?P<post_n2>[0-9]+)?))?
        (?P<dev>[-_.]?(?P<dev_l>dev)[-_.]?(?P<dev_n>[0-9]+)?)?
        (?:\+(?P<local>[a-z0-9]+(?:[-_.][a-z0-9]+)*))?
    )
    \s*$
""", re.VERBOSE | re.IGNORECASE)

_PRE_RELEASE_SPELLINGS = {'alpha': 'a', 'beta': 'b', 'c': 'rc', 'pre': 'rc', 'preview': 'rc'}


def normalize_pep440(version):
    """The canonical PEP 440 form of a version (what setuptools stamps), or
    None when the version is not PEP 440 at all: 1.0.0-rc.1 -> 1.0.0rc1."""
    match = _PEP440_VERSION.match(version)
    if not match:
        return None
    parts = []
    if match.group('epoch') and int(match.group('epoch')) != 0:
        parts.append(f"{int(match.group('epoch'))}!")
    parts.append('.'.join(str(int(n)) for n in match.group('release').split('.')))
    if match.group('pre'):
        letter = match.group('pre_l').lower()
        parts.append(f"{_PRE_RELEASE_SPELLINGS.get(letter, letter)}"
                     f"{int(match.group('pre_n') or 0)}")
    if match.group('post'):
        parts.append(f".post{int(match.group('post_n1') or match.group('post_n2') or 0)}")
    if match.group('dev'):
        parts.append(f".dev{int(match.group('dev_n') or 0)}")
    if match.group('local'):
        parts.append('+' + '.'.join(
            str(int(segment)) if segment.isdigit() else segment.lower()
            for segment in re.split(r'[-_.]', match.group('local'))))
    return ''.join(parts)


def not_a_wheel(wheel_path, reason):
    print(f"Error: {wheel_path} is not a wheel: {reason}", file=sys.stderr)
    sys.exit(1)


def wheel_metadata(wheel_path):
    """Read the distribution, version, build tag and tags a wheel declares
    about itself.

    A wheel's single `<distribution>-<version>.dist-info` directory carries
    the name and the PEP 440 version setuptools stamped into its METADATA,
    and the WHEEL file inside it lists the wheel's tags and, optionally, its
    build number. Fails loudly when the file is not a wheel.
    """
    try:
        with zipfile.ZipFile(wheel_path) as wheel:
            dist_infos = sorted({
                name.split('/', 1)[0] for name in wheel.namelist()
                if '/' in name and name.split('/', 1)[0].endswith('.dist-info')
            })
            if len(dist_infos) != 1:
                not_a_wheel(wheel_path, f"it has {len(dist_infos)} .dist-info "
                                        f"directories, not one")
            dist_info = dist_infos[0]
            wheel_file = wheel.read(f"{dist_info}/WHEEL").decode('utf-8')
    except KeyError:
        not_a_wheel(wheel_path, f"{dist_info} has no WHEEL file")
    except (zipfile.BadZipFile, OSError, UnicodeDecodeError) as e:
        not_a_wheel(wheel_path, e)

    stem = dist_info[:-len('.dist-info')]
    if stem.count('-') != 1:
        not_a_wheel(wheel_path, f"cannot read <distribution>-<version> from {dist_info}")
    distribution, version = stem.split('-')
    fields = [line.split(':', 1) for line in wheel_file.splitlines() if ':' in line]
    tags = [value.strip() for key, value in fields if key.strip().lower() == 'tag']
    builds = [value.strip() for key, value in fields if key.strip().lower() == 'build']
    if not tags:
        not_a_wheel(wheel_path, f"{dist_info}/WHEEL lists no Tag")
    if len(builds) > 1 or (builds and not re.fullmatch(r'[0-9][0-9A-Za-z._]*', builds[0])):
        not_a_wheel(wheel_path, f"{dist_info}/WHEEL has a malformed Build {builds}")
    return distribution, version, (builds[0] if builds else None), tags


def compressed_tag_set(tags):
    """The tag component of a wheel filename (PEP 425 compressed tag set).

    Bundles are pure-python and carry one tag (py3-none-any); several tags
    compress into dotted fields (py2.py3-none-any) when they form a product.
    """
    fields = [[], [], []]
    for tag in tags:
        parts = tag.split('-')
        if len(parts) != 3:
            print(f"Error: malformed wheel tag {tag!r}", file=sys.stderr)
            sys.exit(1)
        for values, part in zip(fields, parts):
            if part not in values:
                values.append(part)
    product = {f"{py}-{abi}-{plat}"
               for py in fields[0] for abi in fields[1] for plat in fields[2]}
    if product != set(tags):
        print(f"Error: wheel tags {tags} do not compress into one filename "
              f"tag set", file=sys.stderr)
        sys.exit(1)
    return '-'.join('.'.join(values) for values in fields)


def pep427_wheel_name(wheel_path, package_name, version):
    """The PEP 427 filename a wheel must be published under.

    The bazel output basename (<target>_bundle.whl) is not a parseable wheel
    filename: pip cannot resolve it from an index, and a registry rejects the
    upload (Artifact Registry answers 400). The name is read from the wheel's
    own .dist-info, normalized as the binary distribution format specifies,
    so it matches the wheel's metadata by construction. The wheel must belong
    to --package-name and carry the bundle's version (compared in canonical
    PEP 440 form), so a misconfigured target or a stale build never publishes.
    """
    distribution, wheel_version, build, tags = wheel_metadata(wheel_path)
    if normalize_project_name(distribution) != normalize_project_name(package_name):
        print(f"Error: {wheel_path} holds distribution {distribution!r}, "
              f"not --package-name {package_name!r}", file=sys.stderr)
        sys.exit(1)
    expected = normalize_pep440(version)
    if expected is None:
        print(f"Error: the bundle's version {version!r} is not a PEP 440 version, "
              f"so no Python wheel can carry it", file=sys.stderr)
        sys.exit(1)
    canonical = normalize_pep440(wheel_version)
    if canonical != expected:
        bundle = (f"is {version!r}" if expected == version
                  else f"{version!r} is {expected!r} in PEP 440 form")
        print(f"Error: {wheel_path} carries version {wheel_version!r}, but the "
              f"bundle's version {bundle}: rebuild the wheel", file=sys.stderr)
        sys.exit(1)
    name = re.sub(r'[-_.]+', '_', distribution).lower()
    fields = [name, canonical.replace('-', '_')] + ([build] if build else [])
    return '-'.join(fields + [compressed_tag_set(tags)]) + '.whl'


def publish_to_local_repo(wheel_path, package_name, wheel_name, repo_path):
    """Publish wheel to local PyPI repository using simple index format"""

    # Per-project directory named per PEP 503, as pip's simple API requires
    package_dir = Path(repo_path) / normalize_project_name(package_name)
    ensure_directory_exists(package_dir)

    # Copy under the wheel's PEP 427 name (see pep427_wheel_name). Bazel
    # outputs are read-only and copy2 would carry that mode over, so a second
    # publish of the same version could not overwrite the first. The content
    # goes to a temporary file beside the target, which is then renamed over
    # any earlier copy: the source is never touched (it may itself be the
    # earlier copy), and pip never sees a half-written wheel.
    target_wheel = package_dir / wheel_name
    if target_wheel.exists() and os.path.samefile(wheel_path, target_wheel):
        print(f"{target_wheel} is already in place")
    else:
        fd, staged = tempfile.mkstemp(dir=package_dir, prefix='.', suffix='.whl.tmp')
        os.close(fd)
        try:
            shutil.copyfile(wheel_path, staged)
            os.chmod(staged, 0o644)
            os.replace(staged, target_wheel)
        finally:
            if os.path.exists(staged):
                os.unlink(staged)
        print(f"Copied wheel to {target_wheel}")

    # Update package index
    update_package_index(package_dir)

    # Update root simple index
    update_root_index(Path(repo_path))

    return target_wheel


def update_package_index(package_dir):
    """Create/update package-specific index.html"""
    # List all wheel files
    wheel_files = sorted([f for f in os.listdir(package_dir) if f.endswith('.whl')])

    # Create index.html
    html_lines = ['<!DOCTYPE html>', '<html>', '<body>']
    for wheel in wheel_files:
        html_lines.append(f'<a href="{wheel}">{wheel}</a><br/>')
    html_lines.extend(['</body>', '</html>'])

    index_path = package_dir / "index.html"
    index_path.write_text('\n'.join(html_lines))
    print(f"Updated package index: {index_path}")


def update_root_index(repo_path):
    """Update root simple index listing all packages"""
    # List all package directories
    packages = []
    for item in sorted(os.listdir(repo_path)):
        item_path = repo_path / item
        if item_path.is_dir() and not item.startswith('.'):
            packages.append(item)

    # Create root index.html
    html_lines = ['<!DOCTYPE html>', '<html>', '<body>']
    for pkg in packages:
        html_lines.append(f'<a href="{pkg}/">{pkg}</a><br/>')
    html_lines.extend(['</body>', '</html>'])

    index_path = repo_path / "index.html"
    index_path.write_text('\n'.join(html_lines))
    print(f"Updated root index: {index_path}")


def publish_to_remote_registry(wheel_path, wheel_name, registry_url, token):
    """Publish wheel to a remote PyPI registry (e.g., GCP Artifact Registry).

    Uploads a copy staged under the wheel's PEP 427 name (see
    pep427_wheel_name): twine and the registry read the distribution, the
    version and the tags from the filename.
    """
    with tempfile.TemporaryDirectory() as staging:
        staged = os.path.join(staging, wheel_name)
        shutil.copyfile(wheel_path, staged)
        upload_to_registry(staged, registry_url, token)
    return wheel_name


def upload_to_registry(wheel_path, registry_url, token):
    """Upload a PEP 427-named wheel with twine, else a stdlib HTTP upload"""
    registry_url = registry_url.rstrip('/')

    # Try twine first
    try:
        result = subprocess.run(
            ['twine', '--version'],
            capture_output=True, text=True,
        )
        has_twine = result.returncode == 0
    except FileNotFoundError:
        has_twine = False

    if has_twine:
        print(f"Uploading with twine to: {registry_url}")
        env = os.environ.copy()
        env['TWINE_USERNAME'] = 'oauth2accesstoken'
        env['TWINE_PASSWORD'] = token
        result = subprocess.run(
            [
                'twine', 'upload',
                '--repository-url', registry_url,
                '--non-interactive',
                wheel_path,
            ],
            capture_output=True,
            text=True,
            env=env,
        )
        if result.returncode != 0:
            print(f"Error uploading with twine: {result.stderr}", file=sys.stderr)
            if result.stdout:
                print(f"  stdout: {result.stdout}", file=sys.stderr)
            sys.exit(1)
        print(f"Successfully uploaded: {os.path.basename(wheel_path)}")
    else:
        # Fallback: HTTP upload using urllib
        print(f"twine not found, using urllib fallback to: {registry_url}")
        import urllib.request
        import urllib.error

        wheel_name = os.path.basename(wheel_path)
        with open(wheel_path, 'rb') as f:
            wheel_data = f.read()

        # Construct multipart form data for PyPI upload
        boundary = '----ProtolakeUploadBoundary'
        fields = [
            (':action', 'file_upload'),
            ('protocol_version', '1'),
        ]
        body = b''
        for field_name, field_value in fields:
            body += f'--{boundary}\r\n'.encode()
            body += f'Content-Disposition: form-data; name="{field_name}"\r\n\r\n'.encode()
            body += f'{field_value}\r\n'.encode()

        body += f'--{boundary}\r\n'.encode()
        body += f'Content-Disposition: form-data; name="content"; filename="{wheel_name}"\r\n'.encode()
        body += b'Content-Type: application/octet-stream\r\n\r\n'
        body += wheel_data
        body += f'\r\n--{boundary}--\r\n'.encode()

        req = urllib.request.Request(
            registry_url + '/',
            data=body,
            method='POST',
            headers={
                'Content-Type': f'multipart/form-data; boundary={boundary}',
                'Authorization': f'Bearer {token}',
            },
        )
        try:
            with urllib.request.urlopen(req) as resp:
                print(f"Uploaded {wheel_name} ({resp.status})")
        except urllib.error.HTTPError as e:
            print(f"Error uploading {wheel_name}: {e.code} {e.reason}", file=sys.stderr)
            resp_body = e.read().decode('utf-8', errors='replace')
            if resp_body:
                print(f"  Response: {resp_body[:500]}", file=sys.stderr)
            sys.exit(1)

    return wheel_path


def main():
    parser = argparse.ArgumentParser(description='Publish Proto Lake bundle to PyPI')
    parser.add_argument('wheel_path', help='Path to the wheel file')
    parser.add_argument('--package-name', required=True,
                        help='PyPI package name')
    parser.add_argument('--version', default=None,
                        help='Version. Shown in publish output and the pip '
                             'install hint, so it must match the version '
                             'stamped in the wheel — resolve via '
                             '--bundle-yaml to stay in sync.')
    parser.add_argument('--bundle-yaml', default=None,
                        help="Path to the bundle's bundle.yaml; used to resolve "
                             'the version when --version is absent')
    parser.add_argument('--repo', default=None,
                        help='Local PyPI repository path or registry URL '
                             '(default: $PYPI_REPO, else ~/.cache/pip/simple)')
    parser.add_argument('--index-url',
                        help='PyPI registry URL (legacy spelling of a URL --repo)')

    args = parser.parse_args()

    # One destination, explicit flags first: --repo, then the legacy --index-url,
    # then PYPI_REPO, which is how protolakew's --pypi-repo and CI name it (the
    # publish target gazelle emits passes neither flag), then the local index.
    # A URL publishes to that registry; anything else is a local index path.
    dest = (args.repo or args.index_url or os.environ.get('PYPI_REPO')
            or os.path.expanduser('~/.cache/pip/simple'))

    if args.version is None:
        if args.bundle_yaml is None:
            parser.error('one of --version or --bundle-yaml is required')
        args.version = read_bundle_version(args.bundle_yaml)

    # Verify wheel exists
    if not os.path.exists(args.wheel_path):
        print(f"Error: Wheel file not found: {args.wheel_path}", file=sys.stderr)
        sys.exit(1)

    try:
        wheel_name = pep427_wheel_name(args.wheel_path, args.package_name, args.version)

        if dest.startswith('https://') or dest.startswith('http://'):
            # Remote registry mode
            token = os.environ.get('REGISTRY_TOKEN', '')
            if not token:
                print("Error: REGISTRY_TOKEN env var required for remote registry",
                      file=sys.stderr)
                sys.exit(1)

            publish_to_remote_registry(args.wheel_path, wheel_name, dest, token)

            print(f"\nSuccessfully published to remote PyPI registry:")
            print(f"  Package: {args.package_name}")
            print(f"  Version: {args.version}")
            print(f"  Registry: {dest}")

        else:
            # Local repository mode
            published_wheel = publish_to_local_repo(
                args.wheel_path,
                args.package_name,
                wheel_name,
                dest
            )

            print(f"\nSuccessfully published to local PyPI repository:")
            print(f"  Package: {args.package_name}")
            print(f"  Version: {args.version}")
            print(f"  Location: {published_wheel}")
            print(f"\nTo install locally:")
            print(f"  pip install --index-url file://{dest} {args.package_name}=={args.version}")

    except Exception as e:
        print(f"Error publishing to PyPI: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()