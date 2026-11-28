// Phase 16: a tiny local "GitHub releases" for testing one-tap update with the side-by-side
// "Earshot update test" app (build type updatetest). Serves every earshot-<version>.apk in [folder],
// its .sha256 (a hand-written <apk>.sha256 in the folder wins, to test a wrong checksum), and releases.json in GitHub's shape (versions with -beta.N are pre-releases).
//
//   node android\tools\update-test-server.mjs C:\repos\earshot-updatetest
//   adb reverse tcp:8000 tcp:8000      (the phone's 127.0.0.1:8000 then reaches this server)
//
// Only listens on 127.0.0.1. Stop it with Ctrl+C.
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync, createReadStream } from "node:fs";
import { join, basename } from "node:path";

const folder = process.argv[2];
if (!folder) {
  console.error("Usage: node update-test-server.mjs <folder with earshot-<version>.apk files>");
  process.exit(1);
}
const PORT = 8000;
const base = `http://127.0.0.1:${PORT}/`;

function apks() {
  return readdirSync(folder)
    .map((name) => /^earshot-(\d+\.\d+\.\d+(?:-beta\.\d+)?)\.apk$/.exec(name))
    .filter(Boolean)
    .map((m) => ({ name: m[0], version: m[1] }));
}

function sha256(file) {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

function releases() {
  return apks().map(({ name, version }) => ({
    tag_name: `v${version}`,
    name: `Earshot ${version} (update test)`,
    draft: false,
    prerelease: version.includes("-beta."),
    html_url: base,
    body: `Update test build ${version}.`,
    assets: [
      { name, browser_download_url: base + name },
      { name: `${name}.sha256`, browser_download_url: `${base}${name}.sha256` },
    ],
  }));
}

createServer((req, res) => {
  const path = decodeURIComponent((req.url ?? "/").split("?")[0]).replace(/^\/+/, "");
  console.log(new Date().toISOString(), req.method, "/" + path);
  if (path === "releases.json") {
    res.writeHead(200, { "Content-Type": "application/json" });
    return res.end(JSON.stringify(releases(), null, 2));
  }
  const known = apks().map((a) => a.name);
  if (path.endsWith(".apk.sha256") && known.includes(path.slice(0, -7))) {
    const apk = path.slice(0, -7);
    res.writeHead(200, { "Content-Type": "text/plain" });
    // A hand-written <apk>.sha256 in the folder wins (to test a wrong checksum).
    const override = join(folder, `${apk}.sha256`);
    try {
      return res.end(readFileSync(override, "utf8"));
    } catch {
      return res.end(`${sha256(join(folder, apk))}  ${apk}\n`);
    }
  }
  if (known.includes(basename(path)) && path === basename(path)) {
    const file = join(folder, path);
    res.writeHead(200, { "Content-Type": "application/vnd.android.package-archive", "Content-Length": statSync(file).size });
    return createReadStream(file).pipe(res);
  }
  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not found\n");
}).listen(PORT, "127.0.0.1", () => {
  console.log(`Update test server on ${base} serving ${folder}:`);
  for (const r of releases()) console.log(`  ${r.tag_name}${r.prerelease ? " (beta)" : ""}`);
});
