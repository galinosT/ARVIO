"use strict";
/**
 * GMC Cloud account admin. Run inside the container:
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs list
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs create <email>     (asks for the password)
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs passwd <email>     (asks for the new password)
 *   docker compose exec -e ARVIO_PASSWORD=... gmc-cloud node gmc-cloud/admin.cjs import-arvio <email> [arvio-email]
 *     copies the ARVIO cloud snapshot of that ARVIO account (default: the same email) into the GMC
 *     Cloud account, replacing what is there. Field times are set to now so the devices take it.
 *   docker compose exec gmc-cloud node gmc-cloud/admin.cjs clear-trakt <email>
 *     removes the Trakt sign-in carried over from the ARVIO cloud (GMC keeps Trakt on each box).
 *
 * Public signup is closed on GMC Cloud; this is the only way an account is created.
 * Passwords are read from the terminal (or GMC_ADMIN_PASSWORD) and never printed.
 */
const readline = require("node:readline");
const { startBlobs, secretFile } = require("./server.cjs");
const accounts = require("./accounts.cjs");

async function askPassword(label) {
  if (process.env.GMC_ADMIN_PASSWORD) return process.env.GMC_ADMIN_PASSWORD;
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  rl._writeToOutput = (s) => { if (!rl.muted) rl.output.write(s); };
  const ask = (q) => new Promise((resolve) => { rl.muted = false; rl.output.write(q); rl.muted = true; rl.question("", (a) => { rl.output.write("\n"); resolve(a); }); });
  const first = await ask(`${label}: `);
  const second = await ask("Again: ");
  rl.close();
  if (first !== second) throw new Error("The two passwords differ");
  return first;
}

async function main() {
  const [cmd, email, arvioEmail] = process.argv.slice(2);
  if (!process.env.ARVIO_AUTH_SECRET) process.env.ARVIO_AUTH_SECRET = secretFile(".auth-secret");
  const blobs = await startBlobs();
  try {
    if (cmd === "list") {
      const all = await accounts.list();
      for (const a of all) console.log(`${a.email}\tcreated ${a.createdAt || "?"}\tid ${a.accountId}`);
      if (!all.length) console.log("(no accounts)");
    } else if (cmd === "create" && email) {
      console.log(await accounts.create(email, await askPassword(`Password for ${email}`)));
    } else if (cmd === "passwd" && email) {
      console.log(await accounts.passwd(email, await askPassword(`New password for ${email}`)));
    } else if (cmd === "import-arvio" && email) {
      console.log(await accounts.importArvio(email, arvioEmail, process.env.ARVIO_PASSWORD || ""));
    } else if (cmd === "clear-trakt" && email) {
      console.log(await accounts.clearTrakt(email));
    } else {
      console.log("Usage: admin.cjs list | create <email> | passwd <email> | import-arvio <email> [arvio-email] | clear-trakt <email>");
      process.exitCode = 2;
    }
  } finally {
    await blobs.stop?.();
  }
}

main().then(() => process.exit(process.exitCode || 0)).catch((e) => { console.error(e.message); process.exit(1); });
