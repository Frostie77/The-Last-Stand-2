"""Generate an independent Flash movie that measures keyboard and mouse input."""
from pathlib import Path
import sys
import struct
destination=Path(sys.argv[1])
u16=lambda n:struct.pack('<H',n)
def action(op,p=b''):return bytes([op])+(u16(len(p))+p if op>=128 else b'')
def push(v):return action(0x96,b'\0'+v.encode()+b'\0' if isinstance(v,str) else b'\7'+struct.pack('<i',v))
def var(s):return push(s)+b'\x1c'
def member(o,s):return o+push(s)+b'\x4e'
def root(s):return member(var('_root'),s)
def call(obj,name,args):return b''.join(push(x) for x in reversed(args))+push(len(args))+obj+push(name)+b'\x52\x17'
def fn(name,body):return action(0x9b,name.encode()+b'\0\0\0'+u16(len(body)+1))+body+b'\0'
def increment(name):return var('_root')+push(name)+root(name)+push(1)+b'\x47\x4f'
def register(name):
 ext=member(member(var('flash'),'external'),'ExternalInterface')
 return var('__'+name)+var('_root')+push(name)+push(3)+ext+push('addCallback')+b'\x52\x17'
s=push('moves')+push(0)+b'\x1d'+push('shots')+push(0)+b'\x1d'
s+=call(var('_root'),'createEmptyMovieClip',['target',1])
target=root('target')
s+=call(target,'beginFill',[0x445566]);s+=call(target,'moveTo',[0,0])
for x,y in [(800,0),(800,420),(0,420),(0,0)]:s+=call(target,'lineTo',[x,y])
s+=call(target,'endFill',[])
s+=target+push('onPress')+fn('',increment('shots'))+b'\x4f'
inc=increment('moves')
body=push(83)+push(1)+var('Key')+push('isDown')+b'\x52\x12'+action(0x9d,struct.pack('<h',len(inc)))+inc
s+=var('_root')+push('onEnterFrame')+fn('',body)+b'\x4f'
state=root('moves')+push('|')+b'\x21'+root('shots')+b'\x21'+push('|')+b'\x21'+push(83)+push(1)+var('Key')+push('isDown')+b'\x52\x21\x3e'
s+=fn('__tls2FixtureState',state)+register('tls2FixtureState')+b'\0'
def tag(t,p=b''):return u16((t<<6)|63)+struct.pack('<I',len(p))+p
bits=f'{15:05b}'+''.join(f'{v:015b}' for v in [0,16000,0,8400]);bits+='0'*(-len(bits)%8)
rect=int(bits,2).to_bytes(len(bits)//8,'big')
body=rect+u16(30*256)+u16(1)+tag(9,b'\x22\x22\x22')+tag(12,s)+u16(1<<6)+u16(0)
destination.write_bytes(b'FWS\x08'+struct.pack('<I',len(body)+8)+body)
print('Generated independent input fixture:',len(body)+8)
