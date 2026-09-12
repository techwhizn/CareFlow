export type CitationSegment = { text: string; citation?: string };
export function citationSegments(
  content: string,
  allowed: string[],
): CitationSegment[] {
  const valid = new Set(allowed);
  const segments: CitationSegment[] = [];
  let offset = 0;
  for (const match of content.matchAll(/\[([^\[\]\n]{1,100})\]/g)) {
    if (!valid.has(match[1])) {
      if (/^[0-9a-f]{8}-[0-9a-f-]{27,}$/i.test(match[1])) {
        segments.push({ text: content.slice(offset, match.index) });
        offset = match.index! + match[0].length;
      }
      continue;
    }
    segments.push({ text: content.slice(offset, match.index) });
    segments.push({ text: match[0], citation: match[1] });
    offset = match.index! + match[0].length;
  }
  segments.push({ text: content.slice(offset) });
  return segments;
}
