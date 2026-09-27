import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const distDir = path.resolve(__dirname, '../dist');

console.log('--- RoomBeat Site Build & Asset Validation Test ---');

// 1. Verify dist directory exists
assert.ok(fs.existsSync(distDir), 'site/dist directory must exist');

// 2. Verify root index.html
const indexPath = path.join(distDir, 'index.html');
assert.ok(fs.existsSync(indexPath), 'dist/index.html must exist');
const indexHtml = fs.readFileSync(indexPath, 'utf-8');
assert.ok(indexHtml.includes('RoomBeat'), 'dist/index.html must contain RoomBeat branding');
assert.ok(indexHtml.includes('SyncSimulator') || indexHtml.includes('canvas') || indexHtml.includes('radar'), 'dist/index.html must include interactive simulator components');
assert.ok(fs.statSync(indexPath).size > 10000, 'dist/index.html must be a populated HTML document');
console.log('✓ Verified dist/index.html (' + fs.statSync(indexPath).size + ' bytes)');

// 3. Verify /privacy/index.html
const privacyPath = path.join(distDir, 'privacy/index.html');
assert.ok(fs.existsSync(privacyPath), 'dist/privacy/index.html must exist');
const privacyHtml = fs.readFileSync(privacyPath, 'utf-8');
assert.ok(privacyHtml.includes('Privacy Policy'), 'dist/privacy/index.html must contain Privacy Policy header');
assert.ok(privacyHtml.includes('239.255.42.99'), 'dist/privacy/index.html must contain multicast IP disclosure');
assert.ok(fs.statSync(privacyPath).size > 5000, 'dist/privacy/index.html must be populated');
console.log('✓ Verified dist/privacy/index.html (' + fs.statSync(privacyPath).size + ' bytes)');

// 4. Verify deployment & SEO metadata
const requiredFiles = ['_headers', 'robots.txt', 'sitemap.xml', 'favicon.svg', 'global.css'];
for (const file of requiredFiles) {
  const filePath = path.join(distDir, file);
  assert.ok(fs.existsSync(filePath), `dist/${file} must exist`);
  assert.ok(fs.statSync(filePath).size > 0, `dist/${file} must not be empty`);
  console.log(`✓ Verified dist/${file}`);
}

// 5. Verify _astro asset bundles
const astroDir = path.join(distDir, '_astro');
assert.ok(fs.existsSync(astroDir), 'dist/_astro asset directory must exist');
const assets = fs.readdirSync(astroDir);
assert.ok(assets.length > 0, 'dist/_astro must contain compiled asset bundles');
console.log(`✓ Verified dist/_astro (${assets.length} bundle files generated)`);

console.log('--- All site validation tests passed successfully! ---');
