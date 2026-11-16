"use strict";
const path = require("node:path");
/** ARVIO's netlify-auth-site checkout that GMC Cloud runs. */
const SITE = path.resolve(process.env.GMC_SITE_DIR || path.join(__dirname, "..", "netlify-auth-site"));
module.exports = { SITE };
