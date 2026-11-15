/* Reuse the current app's native DmSegMobile RPC and protobuf decoder, not the old XML API. */
function __djStartScan(owner){
 const info=owner.context&&owner.context.workinfoManager.workInfo;if(!info)return;
 const key=owner.key(),duration=Number(info.duration),aid=String(info.workId||''),cid=String(info.videoId||'');
 if(!aid||!cid||aid==='0'||cid==='0'||!Number.isFinite(duration)||duration<=0)return;
 if(owner.scan&&owner.scan.key===key)return;
 const scan={key:key,total:Math.ceil(duration/360000),completed:0,failed:[],index:1};owner.scan=scan;
 cron.log('[DanmuJumpScan]'+JSON.stringify({event:'begin',videoId:cid,totalSegments:scan.total}));
 const current=()=>owner.scan===scan&&owner.key()===key;
 const step=()=>{
  if(!current())return;
  if(scan.index>scan.total){cron.log('[DanmuJumpScan]'+JSON.stringify({event:'complete',videoId:cid,completed:scan.completed,totalSegments:scan.total,failedSegments:scan.failed}));return;}
  const index=scan.index++,from=(index-1)*360,to=Math.min(index*360,duration/1000);
  // ps/pe are the native loader's second-based bounds; dC converts them to request milliseconds.
  Promise.resolve().then(()=>dC(owner.context,aid,cid,{index:index,ps:from,pe:to})).then(data=>{
   try{
    if(!data)throw new Error('empty native response');if(!current())return;
    const reply=Sn.DmSegMobileReply.fromBinary(__DJ_DATA.cronDataToUint8Array(data)),selected=[];
    for(const row of reply.elems||[]){const text=String(row.content||''),appearance=Number(row.progress);
     if(!Number.isFinite(appearance)||appearance<0)continue;
     for(const parsed of globalThis.__danmuJumpParseTimes(text))if(parsed.targetMs>60000&&parsed.targetMs>appearance&&parsed.targetMs<duration)
      selected.push({id:String(row.idStr||row.id||'')+'@'+appearance,dmid:String(row.idStr||row.id||''),text:text,
       sentAtSeconds:Number(row.ctime)||null,appearanceMs:appearance,targetMs:parsed.targetMs,explicit:parsed.explicit,format:parsed.format});
    }
    scan.completed++;owner.add({workId:aid,videoId:cid,rows:selected});
    cron.log('[DanmuJumpScan]'+JSON.stringify({event:'segment',videoId:cid,index:index,total:(reply.elems||[]).length,candidates:selected.length,completed:scan.completed,totalSegments:scan.total}));
   }finally{if(data&&typeof data.release==='function')data.release();}
  }).catch(error=>{if(current()){scan.failed.push(index);cron.log('[DanmuJumpScan]'+JSON.stringify({event:'failed',videoId:cid,index:index,error:String(error)}));}})
   .then(()=>{if(current())step();});
 };
 step();
}
