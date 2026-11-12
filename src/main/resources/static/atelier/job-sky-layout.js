export const starSlots = [
  { id:'java', position:[14,63], scale:1.04, depth:.025, image:'star_unknown_0_87b7b9819b.png', matches:/\bJava\b|后端/i, preferred:/\bJava\b.*后端/i },
  { id:'frontend', position:[76,38], scale:1.17, depth:.04, image:'star_7ace939b54.png', matches:/前端|全栈|Front.end/i, preferred:/前端|Front.end/i },
  { id:'data', position:[39,71], scale:1.00, depth:.015, image:'star_80153f61f1.png', matches:/数据|分析|\bBI\b/i },
  { id:'ai', position:[44,39], scale:.82, depth:-.035, image:'star_cd814e141a.png', matches:/大模型|算法|机器学习|\bAI\b/i }
];
export const observatoryMedia = '/atelier/visuals/cosmic/';

export function starTexture(id) {
  return observatoryMedia+(starSlots.find(slot=>slot.id === id) || starSlots[0]).image;
}

export function starPath(job, star) {
  const params = new URLSearchParams({planet:job});
  if (star) params.set('star',star);
  return 'jobs?'+params;
}

export function postingPath(job, star) {
  const params = new URLSearchParams({work:'1',posting:job});
  if (star) params.set('star',star);
  return 'jobs?'+params;
}
