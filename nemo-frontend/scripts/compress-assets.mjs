import {readdir,readFile,writeFile} from 'node:fs/promises'
import {join} from 'node:path'
import {gzipSync} from 'node:zlib'
import console from 'node:console'
let raw=0,packed=0,files=0
async function visit(dir){
  for(const entry of await readdir(dir,{withFileTypes:true})){
    const file=join(dir,entry.name)
    if(entry.isDirectory()){await visit(file);continue}
    if(!/\.(js|css|svg|json)$/.test(entry.name))continue
    const data=await readFile(file)
    if(data.length<1024)continue
    const zipped=gzipSync(data,{level:6})
    if(zipped.length>=data.length)continue
    await writeFile(file+'.gz',zipped);raw+=data.length;packed+=zipped.length;files++
  }
}
await visit('dist/assets')
console.log(`Static gzip: ${files} files, ${raw} -> ${packed} bytes (${Math.round(100*packed/raw)}%)`)
