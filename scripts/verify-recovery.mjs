import { spawn, spawnSync } from 'node:child_process'
import { mkdirSync, copyFileSync, writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..')
const javaInfo=spawnSync('java',['-XshowSettings:properties','-version'],{encoding:'utf8',windowsHide:true})
const javaHome=javaInfo.stderr.match(/java.home = (.+)/)?.[1].trim()
const javaExecutable=javaHome?resolve(javaHome,'bin',process.platform==='win32'?'java.exe':'java'):'java'
const lab=resolve(root,'.tools/recovery-lab-'+Date.now());mkdirSync(lab,{recursive:true})
const auth='Basic '+Buffer.from('admin:Demo2026!').toString('base64')
const base='http://127.0.0.1:8082/api/v1'
let child
const delay=ms=>new Promise(r=>setTimeout(r,ms))
async function request(path,method='GET',body){const r=await fetch(base+path,{method,headers:{Authorization:auth,'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()},body:body?JSON.stringify(body):undefined});assert.ok(r.ok,`${path}: ${r.status} ${await (!r.ok?r.text():Promise.resolve(''))}`);return r.json()}
async function start(db,slow=25){
 child=spawn(javaExecutable,['-jar',resolve(root,'backend/target/agentlens.jar'),'--server.port=8082',`--spring.datasource.url=jdbc:h2:file:${db.replaceAll('\\','/')};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0`,`--agentlens.mock-delay-ms=${slow}`],{cwd:root,windowsHide:true,stdio:'ignore'})
 for(let i=0;i<100;i++){try{const r=await fetch(base+'/health');if(r.ok)return}catch{}await delay(200)}throw new Error('Recovery server did not start')
}
async function stop(){if(!child)return;const c=child;child=undefined;const exited=new Promise(r=>c.once('exit',r));c.kill('SIGKILL');await exited;await delay(150)}
async function waitFor(fn,timeout=45000){const end=Date.now()+timeout;while(Date.now()<end){const result=await fn();if(result)return result;await delay(150)}throw new Error('Verification timed out')}
const evidence={date:new Date().toISOString(),environment:'Java local H2; dedicated port 8082; separate test database',checks:[]}
try{
 const db=resolve(lab,'original')
 await start(db,2000)
 const agent=await request('/agents','POST',{project_id:'project-demo',name:'recovery-mock',adapter:'MOCK',variant:'B',timeout_seconds:5,idempotent:true})
 const run=await request('/runs','POST',{project_id:'project-demo',dataset_id:'dataset-demo-v1',agent_id:agent.id,name:'Restart verification',max_requests:50,max_retries:0})
 const before=await waitFor(async()=>{const r=await request('/runs/'+run.id);return r.results.some(x=>x.status==='SUCCEEDED')&&r.results.some(x=>x.status==='RUNNING')?r:null})
 const saved=before.results.filter(r=>r.status==='SUCCEEDED').map(r=>({id:r.id,output:r.output}))
 await stop();await start(db)
 const after=await waitFor(async()=>{const r=await request('/runs/'+run.id);return ['COMPLETED','COMPLETED_WITH_ERRORS'].includes(r.status)?r:null})
 for(const r of saved)assert.equal(after.results.find(x=>x.id===r.id).output,r.output)
 assert.ok(after.results.some(r=>r.status==='NEEDS_REVIEW'))
 evidence.checks.push({name:'Worker process interruption and restart',passed:true,preserved_completed:saved.length,interrupted_needs_review:after.results.filter(r=>r.status==='NEEDS_REVIEW').length,final_status:after.status})
 const jsonl=Array.from({length:100},(_,i)=>JSON.stringify({case_id:'PERF-'+i,input:'Mock latency test',reference:'ok',checks:{contains:['ok']},demo_index:8})).join('\n')
 const dataset=await request('/datasets/import','POST',{project_id:'project-demo',name:'100-case smoke performance',jsonl})
 await request('/datasets/'+dataset.id+'/publish','POST')
 await stop();await start(db,200)
 const started=Date.now();const perf=await request('/runs','POST',{project_id:'project-demo',dataset_id:dataset.id,agent_id:'agent-demo-B',max_requests:100})
 const result=await waitFor(async()=>{const r=await request('/runs/'+perf.id);return r.status==='COMPLETED'?r:null},60000)
 assert.equal(result.metrics.pass,100);const seconds=(Date.now()-started)/1000;assert.ok(seconds<300)
 evidence.checks.push({name:'100 mock cases, 200 ms response, concurrency 2',passed:true,seconds,completed:result.metrics.done,pass:result.metrics.pass})
 await stop()
 copyFileSync(db+'.mv.db',resolve(lab,'restored.mv.db'))
 await start(resolve(lab,'restored'))
 const restored=await request('/runs/'+run.id);assert.equal(restored.manifest_hash,after.manifest_hash);assert.equal(restored.results.length,25)
 evidence.checks.push({name:'Stopped database backup copied and restored',passed:true,restored_run:run.id,results:restored.results.length})
 mkdirSync(resolve(root,'docs'),{recursive:true});writeFileSync(resolve(root,'docs/recovery-verification.json'),JSON.stringify(evidence,null,2));console.log(JSON.stringify(evidence,null,2))
}finally{await stop()}
