#!/usr/bin/env node
// Dumb font machinery for subset_icons.clj: woff2 <-> ttf conversion (wawoff2)
// and gid-level glyph subsetting (harfbuzz compiled to wasm). Which glyphs to
// keep — and verifying the result — is decided by the babashka script; nothing
// here understands icons.
//
// usage: font_tool.mjs decompress <in.woff2> <out.ttf>
//        font_tool.mjs subset <in.ttf> <out.woff2> <gids-csv> <unicodes-csv>

import { readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import wawoff2 from 'wawoff2';

const require = createRequire(import.meta.url);

async function subsetTtf(ttf, gids, unicodes) {
  const wasm = await readFile(require.resolve('harfbuzzjs/hb-subset.wasm'));
  const { instance: { exports: hb } } = await WebAssembly.instantiate(wasm);
  // re-derive on use: growing wasm memory detaches earlier views
  const heap = () => new Uint8Array(hb.memory.buffer);

  const input = hb.hb_subset_input_create_or_fail();
  if (!input) throw new Error('hb_subset_input_create_or_fail failed');

  const fontPtr = hb.malloc(ttf.byteLength);
  heap().set(new Uint8Array(ttf), fontPtr);
  const blob = hb.hb_blob_create(fontPtr, ttf.byteLength, 2 /* HB_MEMORY_MODE_WRITABLE */, 0, 0);
  const face = hb.hb_face_create(blob, 0);
  hb.hb_blob_destroy(blob);

  // retain every layout feature present in the font
  const features = hb.hb_subset_input_set(input, 6 /* HB_SUBSET_SETS_LAYOUT_FEATURE_TAG */);
  hb.hb_set_clear(features);
  hb.hb_set_invert(features);

  // the glyph list is exact; closure over GSUB would re-add every ligature
  hb.hb_subset_input_set_flags(
    input,
    hb.hb_subset_input_get_flags(input) | 0x200 /* HB_SUBSET_FLAGS_NO_LAYOUT_CLOSURE */);

  const glyphSet = hb.hb_subset_input_glyph_set(input);
  for (const gid of gids) hb.hb_set_add(glyphSet, gid);
  const unicodeSet = hb.hb_subset_input_unicode_set(input);
  for (const cp of unicodes) hb.hb_set_add(unicodeSet, cp);

  const subset = hb.hb_subset_or_fail(face, input);
  hb.hb_subset_input_destroy(input);
  if (!subset) throw new Error('hb_subset_or_fail failed');

  const result = hb.hb_face_reference_blob(subset);
  const offset = hb.hb_blob_get_data(result, 0);
  const length = hb.hb_blob_get_length(result);
  if (!length) throw new Error('harfbuzz produced an empty subset');
  const out = Buffer.from(heap().subarray(offset, offset + length));

  hb.hb_blob_destroy(result);
  hb.hb_face_destroy(subset);
  hb.hb_face_destroy(face);
  hb.free(fontPtr);

  return out;
}

const [cmd, ...args] = process.argv.slice(2);
if (cmd === 'decompress') {
  const [src, dst] = args;
  await writeFile(dst, Buffer.from(await wawoff2.decompress(await readFile(src))));
} else if (cmd === 'subset') {
  const [src, dst, gidsCsv, unicodesCsv] = args;
  const ttf = await subsetTtf(await readFile(src),
                              gidsCsv.split(',').map(Number),
                              unicodesCsv.split(',').map(Number));
  await writeFile(dst, Buffer.from(await wawoff2.compress(ttf)));
} else {
  console.error('usage: font_tool.mjs decompress <in.woff2> <out.ttf> | subset <in.ttf> <out.woff2> <gids-csv> <unicodes-csv>');
  process.exit(1);
}
