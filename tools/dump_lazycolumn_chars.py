from pathlib import Path
p=Path(r"g:\Paul\Visual Studio\BloodSugarApp\app\src\main\java\com\bloodsugar\app\ui\AddReadingScreen.kt")
b=p.read_bytes()
lines=b.splitlines()
start=170
end=186
for i in range(start,end):
    if i < len(lines):
        line=lines[i]
        print(f"{i+1}: bytes={line}\n repr={line!r}\n codepoints=[{', '.join(hex(c) for c in line)}]\n")

