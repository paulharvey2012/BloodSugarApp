import sys
from pathlib import Path
p=Path(r"g:\Paul\Visual Studio\BloodSugarApp\app\src\main\java\com\bloodsugar\app\ui\AddReadingScreen.kt")
s=p.read_text(encoding='utf-8')
paren=0
brace=0
brack=0
lines=s.splitlines()
for i,l in enumerate(lines, start=1):
    for c in l:
        if c=='(':
            paren+=1
        elif c==')':
            paren-=1
        elif c=='{':
            brace+=1
        elif c=='}':
            brace-=1
        elif c=='[':
            brack+=1
        elif c==']':
            brack-=1
    print(f"{i:4d}: paren={paren:3d} brace={brace:3d} brack={brack:3d} | {l}")

print('\nFINAL COUNTS:', 'paren', paren, 'brace', brace, 'brack', brack)
if paren!=0 or brace!=0 or brack!=0:
    sys.exit(2)
else:
    sys.exit(0)

