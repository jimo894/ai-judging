import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
const routes=[
 ['get','/health','健康检查'],['get','/me','当前账号'],['get','/projects','项目列表'],['post','/projects','创建项目'],['post','/projects/{id}/members','项目成员权限'],
 ['get','/datasets','数据集列表'],['post','/datasets/import','导入 JSONL 草稿'],['post','/datasets/{id}/publish','发布不可变版本'],['get','/agents','Agent 列表'],['post','/agents','创建 Agent 快照'],['post','/agents/{id}/test','连接测试'],
 ['get','/runs','运行列表'],['post','/runs','创建运行'],['get','/runs/{id}','运行详情'],['post','/runs/{id}/cancel','取消运行'],['get','/results/{id}','案例详情'],['post','/results/{id}/reviews','人工复核'],['get','/comparisons','版本配对比较'],['post','/traces/batch','接收步骤'],['get','/reports','报告列表'],['post','/reports','创建报告快照'],['get','/reports/{id}/html','下载离线 HTML'],['get','/audit','审计列表']
]
const paths={}
for(const [method,path,summary]of routes){
 const op={summary,operationId:method+'_'+path.replaceAll(/[^a-zA-Z0-9]/g,'_'),responses:{[method==='post'&&path==='/runs'?'202':'200']:{description:'成功'},'400':{description:'格式或字段错误'},'401':{description:'未认证'},'403':{description:'无项目权限'},'409':{description:'状态或幂等冲突'},'422':{description:'不可比较或连接失败'}},parameters:[]}
 if(path.includes('{id}'))op.parameters.push({name:'id',in:'path',required:true,schema:{type:'string'}})
 if(method==='get'&&['/datasets','/agents','/runs','/reports','/audit'].includes(path))op.parameters.push({name:'project_id',in:'query',required:true,schema:{type:'string'}})
 if(path==='/comparisons')for(const name of ['baseline_run_id','candidate_run_id'])op.parameters.push({name,in:'query',required:true,schema:{type:'string'}})
 if(method==='post'&&path==='/runs')op.parameters.push({name:'Idempotency-Key',in:'header',required:true,schema:{type:'string',maxLength:100}})
 if(path==='/health')op.security=[]
 if(method==='post'&&!/\/(publish|test|cancel)$/.test(path))op.requestBody={required:true,content:{'application/json':{schema:{type:'object',description:'字段定义见 API与数据契约.md'}}}}
 if(path.endsWith('/html'))op.responses['200'].content={'text/html':{schema:{type:'string'}}}
 paths[path]??={};paths[path][method]=op
}
const spec={openapi:'3.0.3',info:{title:'AgentLens Demo API',version:'0.1.0',description:'单实例演示平台；请求与响应详细业务字段见同目录 API与数据契约.md。列表暂不分页。'},servers:[{url:'http://127.0.0.1:8080/api/v1'}],security:[{basicAuth:[]}],components:{securitySchemes:{basicAuth:{type:'http',scheme:'basic'}}},paths}
writeFileSync(fileURLToPath(new URL('../contracts/openapi.json',import.meta.url)),JSON.stringify(spec,null,2)+'\n')
console.log('Generated OpenAPI route contract')
