/* Consensus collector. File name retained for the existing resource loader; no popup UI. */
function __djConsensus(rows,duration,tolerance,minVotes){
 const byTarget=rows.filter(r=>Number.isFinite(r.targetMs)&&r.targetMs>60000&&r.targetMs<duration&&r.targetMs>r.appearanceMs)
  .sort((a,b)=>a.targetMs-b.targetMs),groups=[],remaining=new Set(byTarget);
 while(remaining.size>=minVotes){
  const candidates=Array.from(remaining);let best=null,right=0;
  for(let left=0;left<candidates.length;left++){
   if(left>0&&candidates[left].targetMs===candidates[left-1].targetMs)continue;
   if(right<left)right=left;
   while(right<candidates.length&&candidates[right].targetMs-candidates[left].targetMs<=2*tolerance)right++;
   const voters=new Map();
   for(let k=left;k<right;k++){
    const row=candidates[k],id=String(row.dmid||row.id);
    const old=voters.get(id);if(!old||(!old.explicit&&row.explicit))voters.set(id,row);
   }
   const members=Array.from(voters.values()).sort((a,b)=>a.targetMs-b.targetMs);
   if(members.length<minVotes)continue;
   const target=members[Math.floor(members.length/2)].targetMs;
   const agree=members.filter(r=>Math.abs(r.targetMs-target)<=tolerance);
   if(agree.length>=minVotes){const group={targetMs:target,votes:agree.length,
    explicitVotes:agree.filter(r=>r.explicit).length,voters:agree};
    if(!best||group.votes>best.votes||(group.votes===best.votes&&group.explicitVotes>best.explicitVotes))best=group;}
  }
  if(!best)break;groups.push(best);
  // Remove this dense region so it cannot produce overlapping duplicate points.
  for(const row of candidates)if(Math.abs(row.targetMs-best.targetMs)<=2*tolerance)remaining.delete(row);
 }
 return groups.sort((a,b)=>a.targetMs-b.targetMs);
}
const __dj={context:null,activity:null,store:new Map(),scan:null,lastTick:0,sequence:0,
 key(){const w=this.context&&this.context.workinfoManager.workInfo;return w?String(w.workId)+'/'+String(w.videoId):'';},
 bucket(key){let b=this.store.get(key);if(!b){b={rows:new Map(),signature:'',revision:0,publishedRevision:-1,publishedDuration:0,publishedScan:''};this.store.set(key,b);
  if(this.store.size>8)this.store.delete(this.store.keys().next().value);}return b;},
 add(args){if(!args||!Array.isArray(args.rows))return;const key=String(args.workId)+'/'+String(args.videoId),b=this.bucket(key);
  for(const row of args.rows)if(row.targetMs>60000&&row.targetMs>row.appearanceMs){
   b.rows.set(String(row.dmid||row.id)+'/'+row.targetMs,row);b.revision++;}
  if(key===this.key())this.publish();},
 clear(){this.scan=null;this.lastTick=0;for(const bucket of this.store.values()){bucket.signature='';bucket.publishedRevision=-1;}cron.log('[DanmuJumpMarkers]'+JSON.stringify({event:'clear'}));},
 bind(context,activity){this.context=context;this.activity=activity;
  context.videoPlayer.addListener({onTimeUpdated:sec=>this.tick(sec)});
  context.workinfoManager.addListener({onWorkWillChange:()=>this.clear(),onCurrentWorkChanged:()=>{this.clear();this.tick(0);},
   onCurrentWorkInfoChanged:()=>{this.lastTick=0;this.tick(0);}});
  this.clear();this.tick(0);cron.log('[DanmuJumpJS] progress marker pipeline ready');},
 tick(sec){if(!__DJ_CONFIG.enabled)return;const stamp=Date.now();if(stamp-this.lastTick<500)return;this.lastTick=stamp;
  this.publish();},
 publish(){if(!__DJ_CONFIG.enabled)return;const info=this.context&&this.context.workinfoManager.workInfo;if(!info)return;
  const duration=Number(info.duration),key=this.key(),b=this.bucket(key);
  if(!Number.isFinite(duration)||duration<=60000)return;
  // Work info may be empty during bind. Worker replies and info updates retry here.
  if(typeof __djStartScan==='function')__djStartScan(this);
  else if(!this.scanUnavailableLogged){this.scanUnavailableLogged=true;cron.log('[DanmuJumpScan] missing scanner function');}
  const scanned=this.scan&&this.scan.completed||0,total=this.scan&&this.scan.total||0,scanKey=scanned+'/'+total;
  if(b.publishedRevision===b.revision&&b.publishedDuration===duration&&b.publishedScan===scanKey)return;
  b.publishedRevision=b.revision;b.publishedDuration=duration;b.publishedScan=scanKey;
  const groups=__djConsensus(Array.from(b.rows.values()),duration,(__DJ_CONFIG.targetToleranceSeconds||5)*1000,__DJ_CONFIG.minVotes||3);
  const points=groups.map(g=>({targetMs:g.targetMs,votes:g.votes,explicitVotes:g.explicitVotes}));
  const signature=JSON.stringify([points,scanned,total]);if(signature===b.signature)return;b.signature=signature;
  const batchId=String(Date.now())+'-'+String(++this.sequence);
  cron.log('[DanmuJumpMarkers]'+JSON.stringify({event:'points',workId:String(info.workId),videoId:String(info.videoId),durationMs:duration,points:points,
   scannedSegments:this.scan&&this.scan.completed||0,totalSegments:this.scan&&this.scan.total||0}));
  cron.log('[DanmuJumpVote]'+JSON.stringify({event:'markers',batchId:batchId,workId:String(info.workId),videoId:String(info.videoId),points:points}));
  for(const group of groups)for(const row of group.voters)cron.log('[DanmuJumpVote]'+JSON.stringify({event:'vote',batchId:batchId,workId:String(info.workId),videoId:String(info.videoId),
   consensusTargetMs:group.targetMs,id:row.id,dmid:row.dmid,text:row.text,sentAtSeconds:row.sentAtSeconds,appearanceMs:row.appearanceMs,targetMs:row.targetMs,format:row.format,explicit:row.explicit}));
 }
};
__DJ_RPC.setMessageHandler('DanmuJump_AddCandidates',request=>{__dj.add(request.args);return new __DJ_RESPONSE({accepted:true});});
