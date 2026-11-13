package com.arflix.tv.server

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/*
 * GMC personal build: the phone side of any text field on the TV.
 *
 * The same idea as the AI key page (AiKeyConfigServer), made general: a small server on this box,
 * reachable only on the home network, that shows the phone a form with the TV dialog's own fields.
 * What the phone sends fills those fields on the TV, where the user still confirms. It can also
 * hand the phone a file (the settings export) and take one back (the settings import).
 *
 * Every request must carry the one-time token that is in the QR code, so nothing else on the
 * network can read or fill the form. The server lives exactly as long as the dialog that opened it.
 */
class GmcPhoneServer private constructor(
    port: Int,
    private val title: String,
    private val fields: List<Field>,
    private val onValues: (List<String>) -> Unit,
    private val download: Download?,
    private val onUpload: ((String) -> String)?,
) : NanoHTTPD(port) {

    /** One field of the TV dialog. [value] prefills the phone form unless [secret]. */
    data class Field(val label: String, val value: String = "", val secret: Boolean = false, val multiline: Boolean = false)

    /** A file the phone can download: its name and, on request, its content. */
    class Download(val fileName: String, val content: () -> String)

    val token: String = generateToken()

    override fun serve(session: IHTTPSession): Response {
        val supplied = session.parameters["t"]?.firstOrNull()
        if (supplied != token) return text(Response.Status.FORBIDDEN, "Ο κωδικός έληξε — σκάναρε ξανά το QR στην τηλεόραση.")
        return when {
            session.method == Method.GET && session.uri == "/" -> html(page())
            session.method == Method.GET && session.uri == "/download" && download != null -> {
                val bytes = download.content().toByteArray(Charsets.UTF_8)
                newFixedLengthResponse(Response.Status.OK, "application/json", ByteArrayInputStream(bytes), bytes.size.toLong())
                    .apply { addHeader("Content-Disposition", "attachment; filename=\"${download.fileName}\"") }
            }
            session.method == Method.POST && session.uri == "/submit" -> {
                val values = readJson(session)?.optJSONArray("values")
                    ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false))
                onValues(List(fields.size) { values.optString(it, "") })
                json(Response.Status.OK, JSONObject().put("ok", true))
            }
            session.method == Method.POST && session.uri == "/upload" && onUpload != null -> {
                val content = readJson(session)?.optString("content").orEmpty()
                if (content.isBlank()) return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false))
                json(Response.Status.OK, JSONObject().put("ok", true).put("message", onUpload.invoke(content)))
            }
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun readJson(session: IHTTPSession): JSONObject? {
        val body = HashMap<String, String>()
        return runCatching {
            session.parseBody(body)
            JSONObject(body["postData"].orEmpty())
        }.getOrNull()
    }

    private fun html(content: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", content)
    private fun text(status: Response.Status, content: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", content)
    private fun json(status: Response.Status, content: JSONObject) =
        newFixedLengthResponse(status, "application/json", content.toString())

    private fun page(): String {
        val fieldsJson = JSONArray().apply {
            fields.forEach {
                put(JSONObject().put("label", it.label).put("value", if (it.secret) "" else it.value)
                    .put("secret", it.secret).put("multiline", it.multiline))
            }
        }
        val config = JSONObject()
            .put("title", title)
            .put("token", token)
            .put("fields", fieldsJson)
            .put("download", download?.fileName ?: JSONObject.NULL)
            .put("upload", onUpload != null)
        return PAGE.replace("/*CONFIG*/null", config.toString().replace("</", "<\\/"))
    }

    companion object {
        private const val FIRST_PORT = 8105

        /** Starts on the first free port from 8105; null when none could be opened. */
        fun start(
            title: String,
            fields: List<Field>,
            onValues: (List<String>) -> Unit = {},
            download: Download? = null,
            onUpload: ((String) -> String)? = null,
        ): GmcPhoneServer? {
            for (port in FIRST_PORT until FIRST_PORT + 10) {
                val server = GmcPhoneServer(port, title, fields, onValues, download, onUpload)
                if (runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }.isSuccess) return server
            }
            return null
        }

        private fun generateToken(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
            val random = SecureRandom()
            return (1..16).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }

        private val PAGE = """
<!DOCTYPE html><html lang="el"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>GMC</title>
<style>
:root{color-scheme:dark}
body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#111;color:#eee}
main{max-width:520px;margin:0 auto;padding:24px 16px 40px}
h1{font-size:20px;margin:0 0 4px}
p.sub{margin:0 0 20px;color:#999;font-size:14px}
label{display:block;font-size:14px;color:#bbb;margin:16px 0 6px}
input,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #333;background:#1c1c1c;color:#fff;font-size:16px}
textarea{min-height:120px;font-family:ui-monospace,monospace;font-size:13px}
.row{display:flex;gap:8px;align-items:center}
.row input{flex:1}
button,a.btn{display:block;box-sizing:border-box;width:100%;margin-top:20px;padding:14px;border:0;border-radius:10px;background:#e5397a;color:#fff;font-size:16px;font-weight:600;text-align:center;text-decoration:none}
button.small{width:auto;margin:0;padding:12px 14px;background:#333;font-weight:400}
.sec{margin-top:28px;padding-top:12px;border-top:1px solid #2a2a2a}
#msg{margin-top:16px;font-size:15px;min-height:20px}
.ok{color:#5ad17a}.err{color:#ff6b6b}
</style></head><body><main>
<h1 id="title"></h1><p class="sub">Ό,τι στείλεις εμφανίζεται στην τηλεόραση. Εκεί πατάς OK για να αποθηκευτεί.</p>
<form id="form"></form><div id="files"></div><div id="msg"></div>
</main><script>
const C=/*CONFIG*/null;
document.getElementById('title').textContent=C.title;
const form=document.getElementById('form'),msg=document.getElementById('msg');
function say(t,ok){msg.textContent=t;msg.className=ok?'ok':'err'}
const inputs=C.fields.map((f,i)=>{
  const l=document.createElement('label');l.textContent=f.label;form.appendChild(l);
  let el;
  if(f.multiline){el=document.createElement('textarea')}else{el=document.createElement('input');el.type=f.secret?'password':'text';el.autocapitalize='off';el.autocomplete='off';el.spellcheck=false}
  el.value=f.value||'';
  if(f.secret){const r=document.createElement('div');r.className='row';const b=document.createElement('button');b.type='button';b.className='small';b.textContent='👁';b.onclick=()=>{el.type=el.type==='password'?'text':'password'};r.appendChild(el);r.appendChild(b);form.appendChild(r)}else{form.appendChild(el)}
  return el});
if(C.fields.length){const b=document.createElement('button');b.type='submit';b.textContent='Αποστολή στην τηλεόραση';form.appendChild(b);
form.onsubmit=async e=>{e.preventDefault();try{const r=await fetch('/submit?t='+C.token,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({values:inputs.map(i=>i.value)})});say(r.ok?'Στάλθηκε ✓ — πάτα OK στην τηλεόραση.':'Δεν στάλθηκε ('+r.status+')',r.ok)}catch(x){say('Δεν στάλθηκε: '+x,false)}}}
const files=document.getElementById('files');
if(C.download){const s=document.createElement('div');s.className='sec';const a=document.createElement('a');a.className='btn';a.href='/download?t='+C.token;a.textContent='Λήψη αρχείου ('+C.download+')';s.appendChild(a);files.appendChild(s)}
if(C.upload){const s=document.createElement('div');s.className='sec';const l=document.createElement('label');l.textContent='Επαναφορά από αρχείο';const f=document.createElement('input');f.type='file';f.accept='.json,application/json';const b=document.createElement('button');b.type='button';b.textContent='Αποστολή αρχείου στην τηλεόραση';
b.onclick=async()=>{const file=f.files[0];if(!file){say('Διάλεξε πρώτα αρχείο',false);return}const content=await file.text();try{const r=await fetch('/upload?t='+C.token,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content})});const j=await r.json().catch(()=>({}));say(j.message||(r.ok?'Στάλθηκε ✓':'Απέτυχε ('+r.status+')'),r.ok)}catch(x){say('Απέτυχε: '+x,false)}};
s.appendChild(l);s.appendChild(f);s.appendChild(b);files.appendChild(s)}
</script></body></html>
"""
    }
}
