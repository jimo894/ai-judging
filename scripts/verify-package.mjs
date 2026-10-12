import { spawn, spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..')
const bundle=resolve(process.argv[2]||root)
const temp=resolve(root,'.tools/package-smoke-'+Date.now());mkdirSync(temp,{recursive:true})
const info=spawnSync('java',['-XshowSettings:properties','-version'],{encoding:'utf8',windowsHide:true})
const jdk=info.stderr.match(/java.home = (.+)/)[1].trim()
const child=spawn(resolve(jdk,'bin',process.platform==='win32'?'java.exe':'java'),['-jar',resolve(bundle,'backend/target/agentlens.jar'),'--server.port=8083'],{cwd:temp,windowsHide:true,stdio:'ignore'})
const base='http://127.0.0.1:8083'
try{
 let ready=false;for(let i=0;i<100;i++){try{if((await fetch(base+'/api/v1/health')).ok){ready=true;break}}catch{}await new Promise(r=>setTimeout(r,200))}assert.ok(ready,'packaged server starts')
 const html=await (await fetch(base)).text();assert.ok(html.includes('AgentLens'))
 const auth='Basic '+Buffer.from('admin:Demo2026!').toString('base64')
 const me=await (await fetch(base+'/api/v1/me',{headers:{Authorization:auth}})).json();assert.equal(me.username,'admin')
 const ds=await (await fetch(base+'/api/v1/datasets?project_id=project-demo',{headers:{Authorization:auth}})).json();assert.equal(ds[0].cases.length,25)
 const evidence={date:new Date().toISOString(),bundle,passed:true,checks:['JAR starts from isolated working directory','Serves built Vue page','Default login succeeds','Seeds 25 simulated cases']}
 writeFileSync(resolve(root,'docs/package-verification.json'),JSON.stringify(evidence,null,2));console.log(JSON.stringify(evidence,null,2))
}finally{const exit=new Promise(r=>child.once('exit',r));child.kill('SIGKILL');await exit}
