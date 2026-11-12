export function params(values) {
  return new URLSearchParams(Object.entries(values).filter(([,value]) => value !== '' && value != null));
}

export async function request(path, { method = 'GET', data, signal, file = false } = {}) {
  const multipart = data instanceof FormData;
  let response;
  try {
    response = await fetch('/api' + path, {
      method, signal,
      headers: data && !multipart ? { 'Content-Type': 'application/json' } : undefined,
      body: multipart ? data : data ? JSON.stringify(data) : undefined
    });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new Error('无法连接启程服务。请确认项目后端已启动。');
  }
  if (file && response.ok && !response.headers.get('Content-Type')?.includes('json')) return response.blob();
  let payload;
  try { payload = await response.json(); }
  catch { throw new Error('当前预览未连接项目 API。'); }
  if (!response.ok || payload.success === false) throw new Error(payload.message || '服务暂时无法完成这个操作。');
  return payload.data;
}

export function download(blob, name) {
  const link = document.createElement('a');
  const url = URL.createObjectURL(blob);
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 15000);
}
