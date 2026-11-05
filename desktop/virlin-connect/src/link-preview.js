export function classifyLink(raw) {
  let url;
  try { url = new URL(String(raw || '').trim()); } catch { return null; }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
  const host = url.hostname.toLowerCase();
  let videoId = null;
  if (host === 'youtu.be') videoId = url.pathname.split('/')[1];
  else if (['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtube-nocookie.com', 'www.youtube-nocookie.com'].includes(host)) {
    if (url.pathname === '/watch') videoId = url.searchParams.get('v');
    else if (/^\/(shorts|live|embed)\//.test(url.pathname)) videoId = url.pathname.split('/')[2];
  }
  if (videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId))
    return { kind: 'youtube', host, url: url.href, embedUrl: `https://www.youtube-nocookie.com/embed/${videoId}` };
  return { kind: 'website', host, url: url.href, embedUrl: null };
}
