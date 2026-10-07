#!/bin/sh
# Murakumo node CLI installer. No sudo, model download, enrollment or background service.
set -eu
for tool in node npm curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing $tool. Install Node.js 22+ (includes npm) and curl, then retry." >&2; exit 1; }
done
node -e 'if(Number(process.versions.node.split(".")[0])<22)process.exit(1)' || { echo 'Node.js 22+ is required.' >&2; exit 1; }
case "$(uname -s)" in Darwin|Linux) ;; *) echo 'Supported: macOS and Linux.' >&2; exit 1;; esac
install_dir=${MURAKUMO_INSTALL_DIR:-"$HOME/.local/share/murakumo-cli"}
bin_dir=${MURAKUMO_BIN_DIR:-"$HOME/.local/bin"}
base=https://raw.githubusercontent.com/kotoba-lang/murakumo/main/release
mkdir -p "$install_dir" "$bin_dir"
install_dir=$(cd "$install_dir" && pwd)
bin_dir=$(cd "$bin_dir" && pwd)
if [ -e "$bin_dir/murakumo" ] || [ -L "$bin_dir/murakumo" ]; then
  [ -L "$bin_dir/murakumo" ] && [ "$(readlink "$bin_dir/murakumo")" = "$install_dir/current/murakumo" ] || { echo "Refusing to overwrite existing $bin_dir/murakumo. Choose MURAKUMO_BIN_DIR." >&2; exit 1; }
fi
staging=$(mktemp -d "$install_dir/.install.XXXXXXXX")
trap 'rm -rf "$staging"' EXIT HUP INT TERM
for file in node.mjs package.json package-lock.json nixos-node.nix; do
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$base/$file" -o "$staging/$file"
done
node - "$staging" <<'JS'
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const hashes={"node.mjs":"f28079071ef1c25a562307c3789ad428368e73f15ca96efd42cdfdf2974e7a0a","package.json":"95fed67104441aee31cc77f93f90e5dd412171a21288b3bb4fe9fdc5b1c1f3db","package-lock.json":"705a22363fb593f18bb8c98943b7e8acb1cefa68a50c527247ebcfe30d296644","nixos-node.nix":"1517cc283dd55f415ee942d63d72c3188abfc109d07807a09d58e8a64efca03e"};
for(const [file,want] of Object.entries(hashes)){
 const got=crypto.createHash('sha256').update(fs.readFileSync(path.join(process.argv[2],file))).digest('hex');
 if(got!==want){console.error('Release checksum mismatch for '+file+'. Retry with the latest installer.');process.exit(1);}
}
JS
npm ci --prefix "$staging" --ignore-scripts --no-audit --no-fund --loglevel error
cat > "$staging/murakumo" <<'LAUNCHER'
#!/bin/sh
set -eu
self=$0
while [ -L "$self" ]; do
 target=$(readlink "$self")
 case "$target" in /*) self=$target;; *) self=$(dirname "$self")/$target;; esac
done
exec node "$(cd "$(dirname "$self")" && pwd)/node.mjs" "$@"
LAUNCHER
chmod +x "$staging/murakumo"
"$staging/murakumo" node --help >/dev/null
release_dir="$install_dir/release-11eaa6fa4504bea9"
if [ ! -d "$release_dir" ]; then mv "$staging" "$release_dir"; fi
# Update only the installer's own links; never replace an existing directory.
[ ! -e "$install_dir/current" ] || [ -L "$install_dir/current" ] || { echo 'Refusing to replace current directory.' >&2; exit 1; }
ln -sfn "$release_dir" "$install_dir/current"
ln -sfn "$install_dir/current/murakumo" "$bin_dir/murakumo"
echo "Installed: $bin_dir/murakumo"
echo "Optional NixOS module: $install_dir/current/nixos-node.nix"
case ":$PATH:" in *":$bin_dir:"*) ;; *) echo "Add $bin_dir to PATH in your shell profile, or use the full path above.";; esac
echo 'Next: murakumo node init'
echo 'Then: murakumo node doctor --model <your-served-model-id>'
