"""Assemble the single-file app: template + patched vosk-browser + jingle + vocabulary + slim model."""
import base64, sys, pathlib

here = pathlib.Path(__file__).parent
tpl = (here / "template.html").read_text()
vosk = (here / "vosk.patched.js").read_text()
assert "</script" not in vosk.lower()
jingle = base64.b64encode(pathlib.Path(sys.argv[1]).read_bytes()).decode()
model = base64.b64encode(pathlib.Path(sys.argv[2]).read_bytes()).decode()
vocab = pathlib.Path(sys.argv[3]).read_text()
out = pathlib.Path(sys.argv[4])

html = (tpl.replace("/*__VOSK__*/", vosk)
           .replace("__JINGLE_B64__", jingle)
           .replace("__VOCAB__", vocab)
           .replace("__MODEL_B64__", model))
out.write_text(html)
print(out, round(len(html) / 1e6, 1), "MB")
