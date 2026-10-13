import os,re,json,time
import psycopg2,requests
from collections import Counter
from concurrent.futures import ThreadPoolExecutor,as_completed
API_KEY=os.environ.get('DEEPSEEK_API_KEY','')
API_URL=os.environ.get('DEEPSEEK_URL','https://api.deepseek.com/chat/completions')
MODEL=os.environ.get('DEEPSEEK_MODEL','deepseek-chat')
if not API_KEY:raise SystemExit('DEEPSEEK_API_KEY is required')
conn=psycopg2.connect(host='127.0.0.1',port=5432,user='careernav',dbname='careernav');cur=conn.cursor();cur.execute("SELECT id,title,city,salary,education,experience,industry,COALESCE(skills_json,'[]') FROM jobs WHERE dataset_year=2025 AND (job_description IS NULL OR job_description='') ORDER BY id");rows=cur.fetchall();print('unfilled jobs',len(rows),flush=True)
def norm(s):return re.sub(r'[^0-9a-z\u4e00-\u9fff]+','',(s or '').lower())
clusters={}
for r in rows:clusters.setdefault(norm(r[1]) or 'unknown',[]).append(r)
limit_arg=int(os.environ.get('AI_BATCH_LIMIT','0'));cluster_items=list(clusters.items())
if limit_arg>0:cluster_items=cluster_items[:limit_arg]
items=[]
for idx,(key,members) in enumerate(cluster_items,1):
 cid=f'c{idx:05d}';skills=[]
 for m in members:
  try:skills+=json.loads(m[7] or '[]')
  except:pass
 skills=list(dict.fromkeys([str(x) for x in skills if str(x).strip()]))[:15];industries=[m[6] for m in members if m[6]];exp=[m[5] for m in members if m[5]];edu=[m[4] for m in members if m[4]]
 items.append({'key':cid,'title':members[0][1],'industries':Counter(industries).most_common(3),'skills':skills,'education':edu[0] if edu else '不限','experience':exp[0] if exp else '不限','member_ids':[m[0] for m in members],'confidence':'HIGH' if skills and exp and exp[0] not in ('','经验不限') else ('MEDIUM' if skills else 'LOW')})
print('clusters',len(items),flush=True)
def parse_json(text):
 text=(text or '').strip()
 if text.startswith('```'):text=re.sub(r'^```(?:json)?\s*','',text);text=re.sub(r'\s*```$','',text)
 start=text.find('{');end=text.rfind('}')
 if start<0 or end<start:return None
 try:return json.loads(text[start:end+1])
 except:return None
def call_batch(batch):
 payload=[{k:v for k,v in x.items() if k not in ('member_ids','confidence')} for x in batch];prompt='你是招聘内容生成助手。请对每个输入岗位生成通用岗位职责和任职要求。仅依据岗位名称、行业、技能、学历和经验，不得编造公司特有业务、福利、团队规模或候选人经历。每条职责3-5点，每条要求3-5点，中文。必须只返回输入中的 key，不得增加 key。返回JSON对象：{"items":[{"key":"c00001","job_description":"1. ...","job_requirements":"1. ..."}]}。输入：'+json.dumps(payload,ensure_ascii=False);body={'model':MODEL,'messages':[{'role':'system','content':'只输出合法JSON，不输出Markdown。'},{'role':'user','content':prompt}],'temperature':0.2,'max_tokens':8000,'response_format':{'type':'json_object'}}
 for attempt in range(1,4):
  try:
   r=requests.post(API_URL,headers={'Authorization':'Bearer '+API_KEY,'Content-Type':'application/json'},json=body,timeout=180);r.raise_for_status();data=parse_json(r.json()['choices'][0]['message']['content'])
   if not data:raise ValueError('invalid json')
   out={}
   for x in data.get('items',[]):
    k=str(x.get('key',''));jd=str(x.get('job_description','')).strip();jr=str(x.get('job_requirements','')).strip()
    if k and len(jd)>=20 and len(jr)>=20:out[k]=(jd,jr)
   return out
  except Exception as e:
   if attempt==3:raise
   time.sleep(attempt*2)
return_out={};errors=[];start=time.time();batches=[items[i:i+15] for i in range(0,len(items),15)]
def process(batch):
 got=call_batch(batch);missing=[x for x in batch if x['key'] not in got]
 if missing and len(batch)>1:
  mid=len(missing)//2;got.update(process(missing[:mid]));got.update(process(missing[mid:]))
 return got
with ThreadPoolExecutor(max_workers=4) as ex:
 futs={ex.submit(process,b):b for b in batches};done=0
 for f in as_completed(futs):
  batch=futs[f]
  try:
   got=f.result();return_out.update(got);update=[];by_key={x['key']:x for x in batch}
   for k,(jd,jr) in got.items():
    x=by_key.get(k)
    if not x:continue
    for jid in x['member_ids']:update.append((jd,jr,MODEL,x['confidence'],jid))
   if update:
    cur.executemany("UPDATE jobs SET job_description=%s,job_requirements=%s,content_origin='AI_GENERATED',ai_model=%s,ai_generated_at=now(),ai_confidence=%s,search_text=COALESCE(search_text,'')||' '||%s||' '||%s WHERE id=%s",[(a,b,c,d,a,b,e) for a,b,c,d,e in update]);conn.commit()
  except Exception as e:errors.append(str(e))
  done+=1
  if done%5==0 or done==len(batches):print('progress',done,'/',len(batches),'clusters_ok',len(return_out),'errors',len(errors),round(time.time()-start,1),'s',flush=True)
cur.execute("SELECT count(*) FROM jobs WHERE dataset_year=2025 AND job_description IS NOT NULL AND job_requirements IS NOT NULL");filled=cur.fetchone()[0]
print(json.dumps({'clusters':len(items),'filled_jobs':filled,'errors':errors[:10],'seconds':round(time.time()-start,1)},ensure_ascii=False,indent=2));cur.close();conn.close()
