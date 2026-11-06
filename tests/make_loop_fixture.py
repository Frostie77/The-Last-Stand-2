"""Independent mutable combat-list fixture for the production AVM1 rewriter."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import make_particle_fixture as f

f.NAMES[:] = ['aliveZombies','roundsOnScreen','activeTraps','enemyOnScreen','deadZombies',
             'j','k','v','i','sum','health','damage','length','mutate']
push,var,member,root,call = f.push,f.var,f.member,f.root,f.call


class Code:
    def __init__(self): self.data=bytearray(); self.labels={}; self.fixups=[]
    def emit(self, data): self.data+=data
    def mark(self, name): self.labels[name]=len(self.data)
    def branch(self, op, name):
        self.emit(f.action(op,b'\0\0')); self.fixups.append((len(self.data)-2,name))
    def finish(self):
        for at,name in self.fixups: self.data[at:at+2]=struct.pack('<h',self.labels[name]-(at+2))
        return bytes(self.data)


def object_value(values):
    return b''.join(push(k)+(v if isinstance(v,bytes) else push(v)) for k,v in values)+push(len(values))+b'\x43'


def transform(function, name, work):
    source=work/'original.bin'; target=work/'rewritten.bin'; source.write_bytes(function)
    subprocess.run(['java','-cp',str(work),'io.github.frostie77.laststand2.LoopFixtureCode',str(source),str(target),name],check=True)
    return target.read_bytes()


def loop_body(field):
    c=Code();c.emit(push('sum')+push(0)+b'\x1d'+push('j')+push(0)+b'\x1d')
    c.mark('outer');c.emit(var('j')+member(var(field),'length')+b'\x48\x12');c.branch(0x9d,'done')
    c.emit(push('k')+push(0)+b'\x1d');c.mark('inner')
    c.emit(var('k')+member(var('roundsOnScreen'),'length')+b'\x48\x12');c.branch(0x9d,'mutate')
    health=var(field)+var('j')+b'\x4e'+push('health')+b'\x4e'
    damage=var('roundsOnScreen')+var('k')+b'\x4e'+push('damage')+b'\x4e'
    c.emit(push('sum')+var('sum')+health+damage+b'\x0c\x47\x1d')
    c.emit(push('v')+var('v')+b'\x50\x1d'+push('k')+var('k')+b'\x50\x1d');c.branch(0x99,'inner')
    c.mark('mutate');c.emit(var('mutate')+b'\x12');c.branch(0x9d,'next')
    c.emit(var('j')+push(0)+b'\x49\x12');c.branch(0x9d,'next')
    c.emit(call(var('roundsOnScreen'),'splice',[0,1])+b'\x17')
    c.mark('next');c.emit(push('j')+var('j')+b'\x50\x1d');c.branch(0x99,'outer')
    c.mark('done');c.emit(var('activeTraps')+push(0)+b'\x4e'+push('damage')+var('sum')+b'\x4f')
    # An incoming branch to GetVariable must keep that instruction intact.
    c.emit(push('i'));c.branch(0x99,'sharedGet');c.mark('sharedGet');c.emit(b'\x1c\x17\0')
    return c.finish()


def build(destination):
    with tempfile.TemporaryDirectory(prefix='tls2-loop-code-') as directory:
        work=Path(directory)
        constants=','.join('"'+n+'"' for n in f.NAMES)
        driver='''package io.github.frostie77.laststand2;
import java.nio.file.*; import java.util.*;
public class LoopFixtureCode {
 public static void main(String[] args) throws Exception {
  Map<String,Integer> pool=new LinkedHashMap<>();String[] names={CONSTANTS};
  for(int i=0;i<names.length;i++) pool.put(names[i],i);
  byte[] data=Files.readAllBytes(Path.of(args[0]));int body=3+(data[1]&255)+((data[2]&255)<<8);
  Files.write(Path.of(args[1]),LastStand2LoopOptimizer.rewrite(args[2],data,0,body,data.length,pool));
 }
}'''.replace('CONSTANTS',constants)
        source=work/'LoopFixtureCode.java';source.write_text(driver)
        subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(work),str(source),
                        str(f.ROOT/'app/src/main/java/io/github/frostie77/laststand2/LastStand2LoopOptimizer.java')],check=True)
        script=f.action(0x88,f.u16(len(f.NAMES))+b''.join(n.encode()+b'\0' for n in f.NAMES))
        for name,field,flags in [('collisionTest','aliveZombies',0x6a),('sortZombieDepths','enemyOnScreen',0x2a),('sortDeadCats','deadZombies',0x2a)]:
            body=loop_body(field)
            header=name.encode()+b'\0'+f.u16(0)+b'\2'+f.u16(flags)+f.u16(len(body))
            original=f.action(0x8e,header)+body
            rewritten=transform(original,name,work)
            for prefix,binary in [('old_',original),('fast_',rewritten)]:
                script+=binary+push(prefix+name)+var(name)+b'\x1d'
    reset=Code()
    for field in ['aliveZombies','enemyOnScreen','deadZombies']:
        reset.emit(push(field)+push(0)+b'\x42\x1d'+push('z')+push(0)+b'\x3c')
        reset.mark('start_'+field);reset.emit(var('z')+var('count')+b'\x48\x12');reset.branch(0x9d,'end_'+field)
        item=object_value([('health',var('z')+var('seed')+b'\x47'+push(1)+b'\x47')])
        reset.emit(call(var(field),'push',[item])+b'\x17'+push('z')+var('z')+b'\x50\x1d');reset.branch(0x99,'start_'+field)
        reset.mark('end_'+field)
    reset.emit(push('roundsOnScreen')+push(0)+b'\x42\x1d'+push('z')+push(0)+b'\x3c')
    reset.mark('rounds');reset.emit(var('z')+var('shots')+b'\x48\x12');reset.branch(0x9d,'roundsEnd')
    reset.emit(call(var('roundsOnScreen'),'push',[object_value([('damage',var('z')+push(1)+b'\x47')])])+b'\x17'+push('z')+var('z')+b'\x50\x1d');reset.branch(0x99,'rounds')
    reset.mark('roundsEnd');reset.emit(push('activeTraps')+object_value([('damage',0)])+push(1)+b'\x42\x1d')
    for field,value in [('v',var('seed')),('i',var('seed')),('mutate',var('doMutate'))]:reset.emit(push(field)+value+b'\x1d')
    script+=f.function('__lookupReset',reset.finish(),['count','shots','seed','doMutate'])+f.register('lookupReset')
    run=Code();run.emit(push('iteration')+push(0)+b'\x3c');run.mark('again')
    run.emit(var('iteration')+var('repeats')+b'\x48\x12');run.branch(0x9d,'finished')
    run.emit(push(0)+var('_root')+var('method')+b'\x52\x17'+push('iteration')+var('iteration')+b'\x50\x1d');run.branch(0x99,'again');run.mark('finished')
    script+=f.function('__lookupRun',run.finish(),['method','repeats'])+f.register('lookupRun')
    fields=[var('sum'),var('v'),var('j'),member(var('roundsOnScreen'),'length'),var('activeTraps')+push(0)+b'\x4e'+push('damage')+b'\x4e']
    state=fields[0]
    for value in fields[1:]:state+=push('|')+b'\x21'+value+b'\x21'
    script+=f.function('__lookupState',state+b'\x3e')+f.register('lookupState')+b'\x07\0'
    bits=f'{15:05b}'+''.join(f'{v:015b}' for v in [0,16000,0,8400]);bits+='0'*(-len(bits)%8)
    rect=int(bits,2).to_bytes(len(bits)//8,'big')
    movie=rect+f.u16(35*256)+f.u16(1)+f.u16((12<<6)|63)+struct.pack('<I',len(script))+script+f.u16(64)+f.u16(0)
    destination.write_bytes(b'FWS\x08'+struct.pack('<I',len(movie)+8)+movie)


if __name__=='__main__':build(Path(sys.argv[1]))
