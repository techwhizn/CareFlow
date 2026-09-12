"""Copy BOOTSTRAP_TOKEN from .env without printing it."""

from pathlib import Path
import shutil
import subprocess

env = Path(".env")
if not env.is_file():
    raise SystemExit(".env not found; run scripts/init-local-env.py first")
token = next(
    (line.split("=", 1)[1] for line in env.read_text().splitlines() if line.startswith("BOOTSTRAP_TOKEN=")),
    "",
)
if not token:
    raise SystemExit("BOOTSTRAP_TOKEN is empty")
command = shutil.which("pbcopy") or shutil.which("wl-copy") or shutil.which("xclip")
if not command:
    raise SystemExit("No clipboard command found (pbcopy, wl-copy, or xclip)")
args = [command]
if command.endswith("xclip"):
    args.extend(["-selection", "clipboard"])
subprocess.run(args, input=token.encode(), check=True)
print("Bootstrap token copied to the system clipboard; it was not printed.")
