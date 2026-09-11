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
for file in node.mjs package.json package-lock.json; do
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$base/$file" -o "$staging/$file"
done
node - "$staging" <<'JS'
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const hashes={"node.mjs":"217288dee0ad3dfb7b3af9aec473fb43dcc55038ad8125aba93088f80483d94f","package.json":"a786286a30b67227ef0e73381fddf4f35c51df4361c34ce68cc4a8c7947dcacc","package-lock.json":"22456888066745a55fae40e659fb7455b4afed3cf7eda5a39be97c7e85789c4a"};
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
release_dir="$install_dir/release-217288dee0ad3dfb"
if [ ! -d "$release_dir" ]; then mv "$staging" "$release_dir"; fi
# Update only the installer's own links; never replace an existing directory.
[ ! -e "$install_dir/current" ] || [ -L "$install_dir/current" ] || { echo 'Refusing to replace current directory.' >&2; exit 1; }
ln -sfn "$release_dir" "$install_dir/current"
ln -sfn "$install_dir/current/murakumo" "$bin_dir/murakumo"
echo "Installed: $bin_dir/murakumo"
case ":$PATH:" in *":$bin_dir:"*) ;; *) echo "Add $bin_dir to PATH in your shell profile, or use the full path above.";; esac
echo 'Next: murakumo node init'
echo 'Then: murakumo node doctor --model <your-served-model-id>'
