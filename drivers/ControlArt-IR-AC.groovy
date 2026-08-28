/**
 * ControlArt IR AC
 *
 * TecnoSimples Tecnologia LTDA
 * contato@tecnosimples.com.br | (14) 99760-6885
 * (c) 2026 TecnoSimples - Todos os direitos reservados.
 *
 * Produto licenciado. Distribuido via Hubitat Package Manager.
 * Uso restrito ao hub licenciado. Ver LICENSE no repositorio.
 * Versao do pacote: 1.0.5 | library embutida: 1.17.0
 */
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
@Field static ConcurrentHashMap<String, String> CA_RX_BUF = new ConcurrentHashMap<String, String>()
@Field static ConcurrentHashMap<String, Object> CA_RX_LOCK = new ConcurrentHashMap<String, Object>()
@Field static final Integer CA_HB_DEFAULT_SEC = 30 
@Field static final Integer CA_RECONNECT_MAX = 60
@Field static final Integer CA_IDLE_CLOSE_SEC = 5
@Field static final Integer CA_RX_BUF_MAX = 8192 
@Field static final String CA_WM = "HPM"
@Field static final Boolean CA_WM_POR_HUB = true
@Field static final String CA_LIB_VER = "1.17.0"
private String caDevKey() { return device.id as String }
private String caWmEnviado(String uid) {
return (CA_WM_POR_HUB && uid) ? "${CA_WM}-${uid}" : CA_WM
}
def caConnect() {
unschedule("caWatchdog") 
unschedule("caHeartbeat") 
unschedule("caReconnectKick")
try { interfaces.rawSocket.close() } catch (Exception ignored) { }
CA_RX_BUF.put(caDevKey(), "")
String ip = (settings.device_IP_address ?: "").trim()
if (!ip) { logError("[TCP] IP não configurado — salve as Preferences."); caSetBoardStatus("offline"); return }
if (!settings.device_port) { logError("[TCP] Porta não configurada."); caSetBoardStatus("offline"); return }
try {
logDebug("[TCP] Conectando em ${ip}:${settings.device_port}...")
interfaces.rawSocket.connect(ip, (settings.device_port as Integer))
state.caLastRx = now()
state.caSeenRx = false 
state.caIntentionalClose = false
state.caLinkDown = false 
if (state.caRequiresRx != true) {
state.caRetries = 0
caSetBoardStatus("online")
}
if (caTransiente()) runIn(CA_IDLE_CLOSE_SEC, "caCloseIdle")
else runIn(caHbSec(), "caHeartbeat")
caAfterConnect()
} catch (Exception e) {
int tries = ((state.caRetries ?: 0) as int) + 1
state.caRetries = tries
int delay = Math.min(30 * tries, CA_RECONNECT_MAX)
logWarn("[TCP] Conexão falhou (tentativa ${tries}): ${e.message} — nova tentativa em ${delay}s")
caSetBoardStatus("offline")
state.caLinkDown = true 
state.caIntentionalClose = false
runIn(delay, "caReconnectKick") 
return 
}
try {
caLicSchedule()
if (!caLicOk()) runIn(5 + (caLicJitter() % 55), "caLicCheckNow")
} catch (Exception e) {
logWarn("[LICENÇA] Agendamento pós-conexão falhou (${e.message}) — link intacto.")
}
}
def caReconnectKick() { caConnect() }
def caDisconnect() {
unschedule("caWatchdog")
unschedule("caHeartbeat")
unschedule("caReconnectKick")
try { interfaces.rawSocket.close() } catch (Exception ignored) { }
CA_RX_BUF.remove(caDevKey())
}
private boolean caTransiente() { return settings?.tcpTransiente == true }
private Boolean caAbrirTransiente() {
String ip = (settings.device_IP_address ?: "").trim()
if (!ip || !settings.device_port) {
logError("[TCP] IP/porta não configurados — salve as Preferences.")
caSetBoardStatus("offline")
return false
}
try {
interfaces.rawSocket.connect(ip, (settings.device_port as Integer))
state.caLinkDown = false
state.caIntentionalClose = false
state.caLastRx = now()
caSetBoardStatus("online") 
logDebug("[TCP] Socket transitório aberto em ${ip}:${settings.device_port}")
return true
} catch (Exception e) {
logWarn("[TCP] Conexão transitória falhou: ${e.message} — nada foi ao fio.")
caSetBoardStatus("offline")
state.caLinkDown = true
return false
}
}
def caCloseIdle() {
if (!caTransiente()) return
state.caIntentionalClose = true
state.caLinkDown = true
try { interfaces.rawSocket.close() } catch (Exception ignored) { }
CA_RX_BUF.put(caDevKey(), "")
logDebug("[TCP] Socket transitório fechado (ocioso ${CA_IDLE_CLOSE_SEC}s).")
}
private int caHbSec() { return Math.max(5, Math.min(300, (settings?.hbInterval ?: CA_HB_DEFAULT_SEC) as int)) }
def caHeartbeat() {
if (caTransiente()) { logDebug("[HEARTBEAT] modo transitório — desarmado"); return }
runIn(caHbSec(), "caHeartbeat")
long idleMs = now() - ((state.caLastRx ?: 0L) as long)
if (state.caRequiresRx == true && state.caSeenRx != true && idleMs > caHbSec() * 3000L) {
caCaseCTrip(idleMs)
return
}
String poll = state.caGwPollable ? "getdevices" : caPollCommand()
if (!poll) return
if (idleMs < caHbSec() * 1000L) return
if (idleMs > caHbSec() * 3000L) {
logWarn("[HEARTBEAT] Sem resposta há ${(long)(idleMs / 1000)}s — offline + reconectando")
caSetBoardStatus("offline")
caConnect()
return
}
logDebug("[HEARTBEAT] Link quieto — poll: ${poll}")
caSendRaw(poll)
}
private void caCaseCTrip(long idleMs) {
unschedule("caHeartbeat") 
unschedule("caReconnectKick")
state.caIntentionalClose = true 
int tries = ((state.caRetries ?: 0) as int) + 1
state.caRetries = tries
caSetBoardStatus("offline")
state.caLinkDown = true 
try { interfaces.rawSocket.close() } catch (Exception ignored) { }
int delay = Math.min(30 * tries, CA_RECONNECT_MAX)
logWarn("[TCP] Conectado mas SEM resposta há ${(long)(idleMs / 1000)}s — a central pode estar " +
"no teto de sessões TCP (a 11ª conecta e fica muda). Nova tentativa em ${delay}s.")
runIn(delay, "caReconnectKick")
}
void caProbeGatewayPoll() {
state.caGwPollable = false
caSendRaw("getdevices")
}
private boolean caPollPermitido(String cmd) {
if (cmd == "getdevices" || cmd == "get_firmware_version") return true
return cmd ==~ /^mdcmd_getmd,\d{1,3},\d{1,3},\d{1,3}$/
}
private Boolean caSendWire(String cmd) {
if (caTransiente() && state.caLinkDown != false && !caAbrirTransiente()) return false
if (state.caLinkDown == true) {
logWarn("[TX] '${cmd}' NÃO enviado — sem link com a central (nada foi ao fio).")
if (state.caIntentionalClose != true) runIn(1, "caReconnectKick")
return false
}
try {
logDebug("[TX] ${cmd}")
interfaces.rawSocket.sendMessage(cmd + "\r\n")
if (caTransiente()) runIn(CA_IDLE_CLOSE_SEC, "caCloseIdle")
return true
} catch (Exception e) {
if (state.caIntentionalClose == true) {
logDebug("[TX] Envio descartado — socket fechado de propósito (backoff do Caso C em andamento)")
return false
}
logWarn("[TX] Falha de envio: ${e.message} — agendando reconexão")
caSetBoardStatus("offline")
runIn(5, "caReconnectKick")
return false
}
}
private Boolean caSendRaw(String cmd) {
String c = (cmd ?: "").trim()
if (!caPollPermitido(c)) {
logWarn("[TX] caSendRaw recusou '${c}' — fora da allowlist de keepalive da library.")
return false
}
return caSendWire(c)
}
Boolean caSend(String cmd) {
if (!cmd?.trim()) { logWarn("[TX] Comando vazio ignorado."); return false }
if (!caLicOk()) {
logWarn("[LICENÇA] Comando '${cmd.trim()}' BLOQUEADO — ${caLicTexto(caLicCache())}. HubUID: ${caHubUID()}")
caLicPublicar()
runIn(5 + (caLicJitter() % 55), "caLicCheckNow")
return false
}
return caSendWire(cmd.trim())
}
def parse(String msg) {
state.caLastRx = now()
if (state.caSeenRx != true) { state.caSeenRx = true; state.caRetries = 0 }
caSetBoardStatus("online")
String txt
try {
txt = new String(hubitat.helper.HexUtils.hexStringToByteArray(msg))
} catch (Exception e) {
logWarn("[RX] Payload não-hex descartado (${e.message})")
return
}
String key = caDevKey()
Object lock = CA_RX_LOCK.computeIfAbsent(key, { k -> new Object() })
List<String> lines = []
synchronized (lock) {
String buf = (CA_RX_BUF.get(key) ?: "") + txt
buf = buf.replace("\r\n", "\n").replace("\r", "\n")
int idx
while ((idx = buf.indexOf("\n")) >= 0) {
String line = buf.substring(0, idx).trim()
buf = buf.substring(idx + 1)
if (line) lines << line
}
if (buf.length() > CA_RX_BUF_MAX) {
logWarn("[RX] Buffer sem terminador excedeu ${CA_RX_BUF_MAX}B — descartado")
buf = ""
}
CA_RX_BUF.put(key, buf)
}
lines.each { String line ->
logDebug("[RX] ${line}")
if (line.equalsIgnoreCase("Parse Error!") || line.equalsIgnoreCase("Parse Error")) {
logWarn("[RX] Placa respondeu 'Parse Error!' (comando anterior inválido?)")
return
}
if (line == "endlistdevices" || line.startsWith("device,")) {
if (!state.caGwPollable) { state.caGwPollable = true; logInfo("[TCP] Gateway responde getdevices → keepalive ativo") }
return
}
try { handleLine(line) }
catch (Exception e) { logError("[RX] handleLine falhou em '${line}': ${e.message}") }
}
}
def socketStatus(String status) {
logDebug("[SOCKET] ${status}")
if (caTransiente()) { state.caLinkDown = true; return }
if (state.caIntentionalClose == true) {
logDebug("[SOCKET] fechamento intencional — sem reconexão concorrente")
return
}
String s = (status ?: "").toLowerCase()
if (s.contains("error") || s.contains("closed") || s.contains("broken") || s.contains("disconnect")) {
logWarn("[SOCKET] ${status} — reconectando em 5s")
caSetBoardStatus("offline")
state.caLinkDown = true 
runIn(5, "caReconnectKick")
}
}
void caSetBoardStatus(String newStatus) {
if (((device.currentValue("boardstatus") ?: "") as String) == newStatus) return
sendEvent(name: "boardstatus", value: newStatus, descriptionText: "${device.displayName} ${newStatus}")
logInfo("[STATUS] boardstatus → ${newStatus}")
}
private String caLicEndpoint() { return "https://script.google.com/macros/s/AKfycbwvNpGsy-9Xs4NRk6eer3HRZBXbkfhHJtqp23aIZJA1KBZJUJZ0OJnQvJ6PF36kcHjl/exec" }
private String caHubUID() {
try { String v = location.hub.zigbeeEui?.toString(); if (v) return v } catch (Exception ignored) { }
return null
}
private String caLicDateStr(long ms) {
def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd")
sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
return sdf.format(new Date(ms))
}
private boolean caLicEvaluate(Map st, long nowMs) {
String uid = caHubUID()
if (!uid) return false 
if (!st || (st.uid as String) != uid) return false 
if (!(((st.status ?: "") as String) in ["active", "trial"])) return false
String expiry = (st.expiry ?: "") as String
if (!expiry) return false
long maxSeen = (st.maxSeen ?: 0L) as long
return caLicDateStr(Math.max(nowMs, maxSeen)) <= expiry
}
private Map caLicApplyCheck(Map st, Map resposta, long nowMs) {
String uid = caHubUID()
long maxSeen = Math.max((st?.maxSeen ?: 0L) as long, nowMs)
if (resposta == null) {
if (st == null) return [uid: uid, status: "unknown", expiry: "", lastCheck: 0L, maxSeen: maxSeen]
Map n = new HashMap(st)
n["maxSeen"] = maxSeen
return n
}
return [uid: uid, status: (resposta.status ?: "notfound") as String,
expiry: (resposta.expiry ?: "") as String,
lastCheck: nowMs, maxSeen: maxSeen]
}
private Map caLicCache() {
return (atomicState.lic instanceof Map) ? (Map) atomicState.lic : null
}
private boolean caLicOk() {
return caLicEvaluate(caLicCache(), now())
}
private String caLicTexto(Map st) {
if (!caHubUID()) return "sem identidade — hub sem zigbeeEui"
if (!st) return "aguardando primeira verificação"
if ((st.uid as String) != caHubUID()) return "aguardando primeira verificação"
if (st.status == "unknown") return "aguardando primeira verificação"
if (caLicEvaluate(st, now())) {
return (st.status == "trial") ? "trial — vence ${st.expiry}" : "ativa"
}
return "inativa — fale com a TecnoSimples"
}
private void caLicPublicar() {
sendEvent(name: "hubUID", value: (caHubUID() ?: "sem identidade"))
sendEvent(name: "licenca", value: caLicTexto(caLicCache()))
}
def caLicCheckNow() {
String uid = caHubUID()
if (!uid) { logError("[LICENÇA] Hub sem zigbeeEui — sem identidade."); caLicPublicar(); return }
Map resposta = null
String wm = caWmEnviado(uid)
logInfo("[LICENÇA] Consultando — marca=${wm}")
try {
httpGet([uri: caLicEndpoint(),
query: [action: "check", product: "controlart", uid: uid, v: CA_LIB_VER, wm: wm],
timeout: 15]) { r ->
if (r?.status == 200 && r.data instanceof Map) resposta = (Map) r.data
}
} catch (Exception e) {
logWarn("[LICENÇA] Consulta falhou (${e.message}) — mantendo última validação boa.")
}
boolean antes = caLicOk()
atomicState.lic = caLicApplyCheck(caLicCache(), resposta, now())
if (!antes && caLicOk() && state.caLinkDown != true) {
logInfo("[LICENÇA] Liberada — repetindo o bootstrap pós-conexão.")
try { caAfterConnect() } catch (Exception e) { logWarn("[LICENÇA] caAfterConnect falhou: ${e.message}") }
}
caLicPublicar()
logInfo("[LICENÇA] ${caLicTexto(caLicCache())} (HubUID ${uid})")
}
private long caLicJitter() { return (device.id as long) }
void caLicSchedule() {
long j = caLicJitter()
schedule("${j % 60} ${7 + (j % 5)} 3/6 * * ?", "caLicDaily")
}
def caLicDaily() {
long last = (caLicCache()?.lastCheck ?: 0L) as long
long limite = caLicOk() ? 4 * 86400_000L : 6 * 3600_000L
if ((now() - last) >= limite) caLicCheckNow()
}
void verificarLicenca() { logInfo("[LICENÇA] Verificação manual — HubUID: ${caHubUID()}"); caLicCheckNow() }
void logDebug(String msg) { if (settings.logEnable) log.debug "${device.displayName}: ${msg}" }
void logInfo(String msg) { log.info "${device.displayName}: ${msg}" }
void logWarn(String msg) { log.warn "${device.displayName}: ${msg}" }
void logError(String msg) { log.error "${device.displayName}: ${msg}" }
def logsOff() {
log.warn "${device.displayName}: debug desativado automaticamente"
device.updateSetting("logEnable", [value: "false", type: "bool"])
}
void caScheduleLogsOff() {
if (settings.logEnable) runIn(1800, "logsOff")
}
@Field static final Integer CA_ARTIFACT_MAX = 3
@Field static final String CA_SEP_DEV = " @ "
String caNormKey(String raw) {
return (raw ?: "").trim().toLowerCase().replaceAll("\\s+", "")
}
Map caLerArtefato(String nome) {
String n = (nome ?: "").trim()
if (!n) throw new IllegalStateException("nenhum arquivo de import configurado")
byte[] b
try {
b = downloadHubFile(n)
} catch (Exception e) {
throw new IllegalStateException("arquivo '${n}' não encontrado ou ilegível no File Manager")
}
if (b == null) throw new IllegalStateException("arquivo '${n}' voltou vazio do File Manager")
Map art
try {
art = (Map) parseJson(new String(b))
} catch (Exception e) {
throw new IllegalStateException("'${n}' não é JSON válido — regere pelo tools/xconfig2hub.py")
}
Integer v = 0
try { v = (art?.v ?: 0) as Integer } catch (Exception ignored) { v = 0 }
if (v < 1) throw new IllegalStateException("'${n}' não parece um arquivo de import (sem 'v')")
if (v > CA_ARTIFACT_MAX) throw new IllegalStateException(
"'${n}' é da versão ${v}; este código lê até a ${CA_ARTIFACT_MAX} — " +
"atualize os drivers ou regere o arquivo com o conversor desta versão")
if (!(art.gateways instanceof List) || !art.gateways) throw new IllegalStateException(
"'${n}' não tem nenhuma central em 'gateways'")
return art
}
Map caCasarDispositivo(Map art, String pref) {
String p = (pref ?: "").trim()
if (!p) throw new IllegalStateException("preencha 'Dispositivo no projeto'")
List todos = []
((art?.gateways ?: []) as List).each { gw ->
((gw?.irrf ?: []) as List).each { b ->
b.gwIp = gw?.ip
b.gwPort = gw?.port
todos << b
}
}
if (!todos) throw new IllegalStateException(
"o arquivo não tem nenhum bloco 'irrf' — regere com o conversor da fatia C")
List<String> rotulos = todos.collect { "${(it.dev ?: '')}${CA_SEP_DEV}${(it.room ?: '')}".toString() }
String dev = p
String room = null
int i = p.indexOf(CA_SEP_DEV)
if (i >= 0) {
dev = p.substring(0, i).trim()
room = p.substring(i + CA_SEP_DEV.length()).trim()
if (room.contains(CA_SEP_DEV)) throw new IllegalStateException(
"'${p}' tem mais de um '${CA_SEP_DEV}' — use 'Dispositivo${CA_SEP_DEV}Ambiente'. " +
"Disponíveis: ${rotulos.join(' | ')}")
}
List casam = todos.findAll { b ->
(((b.dev ?: "") as String).trim() == dev) &&
(room == null || ((b.room ?: "") as String).trim() == room)
}
if (casam.size() == 1) return (Map) casam[0]
if (!casam) throw new IllegalStateException(
"nenhum dispositivo '${p}' no arquivo. Disponíveis: ${rotulos.join(' | ')}")
throw new IllegalStateException(
"'${p}' casa com ${casam.size()} dispositivos — use a forma qualificada. Opções: " +
casam.collect { "${(it.dev ?: '')}${CA_SEP_DEV}${(it.room ?: '')}" }.join(' | '))
}
String caIpDoProjeto(Map bloco, Boolean aplicar) {
String doArq = ((bloco?.gwIp ?: "") as String).trim()
if (!doArq) return null
String meu = ((settings?.device_IP_address ?: "") as String).trim()
Integer porta = null
try { porta = (bloco?.gwPort ?: 0) as Integer } catch (Exception ignored) { porta = null }
if (!meu) {
if (!aplicar) return null
device.updateSetting("device_IP_address", [value: doArq, type: "text"])
if (porta) device.updateSetting("device_port", [value: porta, type: "number"])
logInfo("[PROJETO] IP preenchido pelo arquivo: ${doArq}:${porta ?: 4998}")
return null
}
if (meu == doArq) return null
return ("o projeto diz que '${bloco?.dev}' emite por ${doArq}, este device está em ${meu} " +
"— confira qual é o certo (código certo pela central errada não dá erro)").toString()
}
Map caResolverImport(List cmds, List alvos, Map ptbr, Closure extra) {
Map porTecla = [:]
List naoCasou = []
List foraDoAlvo = []
((cmds ?: []) as List).each { c ->
String alias = ((c?.key ?: "") as String)
String pay = ((c?.payload ?: "") as String).trim()
String n = caNormKey(alias)
String alvo = null
if (alvos.contains(n)) {
alvo = n
} else if (extra != null) {
try { alvo = extra(n) as String } catch (Exception ignored) { alvo = null }
}
if (alvo == null && ptbr != null) alvo = ptbr[n] as String
if (alvo == null || !pay) { naoCasou << alias; return }
if (!alvos.contains(alvo)) { foraDoAlvo << "${alias} -> ${alvo}".toString(); return }
if (!porTecla.containsKey(alvo)) porTecla[alvo] = []
porTecla[alvo] << [alias: alias, payload: pay]
}
Map grava = [:]
Map conflitos = [:]
porTecla.each { tecla, itens ->
Set pays = (itens as List).collect { it.payload } as Set
if (pays.size() == 1) grava[tecla] = (itens as List)[0].payload
else conflitos[tecla] = (itens as List).collect { it.alias }
}
return [grava: grava, conflitos: conflitos, naoCasou: naoCasou, foraDoAlvo: foraDoAlvo]
}
Map caImportarDoProjeto(List alvos, Map ptbr, Closure extra, Closure ler, Closure gravar) {
Map bloco
try {
bloco = caCasarDispositivo(caLerArtefato(settings?.projArquivo as String),
settings?.projDispositivo as String)
} catch (Exception e) {
logError("[IMPORT] ${e.message}")
sendEvent(name: "importStatus", value: "erro")
sendEvent(name: "importDetail", value: e.message)
return [erro: e.message]
}
String avisoIp = caIpDoProjeto(bloco, true)
if (avisoIp) logWarn("[PROJETO] ${avisoIp}")
Map r = caResolverImport((bloco.cmds ?: []) as List, alvos, ptbr, extra)
Integer div = 0
((r.grava ?: [:]) as Map).each { k, v -> gravar(k, v) }
List<String> conflitosDetalhe = []
((r.conflitos ?: [:]) as Map).each { k, aliases ->
div++
Boolean armada = ler(k) as Boolean
String sufixoLog = armada ? " A tecla CONTINUA ARMADA com o código anterior " +
"(conflito-armado) — confira à mão." : ""
conflitosDetalhe << ("'${k}'" + (armada ? " (conflito-armado)" : "")).toString()
logWarn("[IMPORT] Conflito em '${k}': ${(aliases as List).join(', ')} têm códigos " +
"DIFERENTES no projeto. Nada foi gravado nela.${sufixoLog}")
}
((r.foraDoAlvo ?: []) as List).each {
div++
logError("[IMPORT] BUG de tabela: '${it}' aponta para uma tecla que este driver não tem. " +
"Reporte — é erro de código, não do seu projeto.")
}
Integer n = ((r.grava ?: [:]) as Map).size()
List naoCasou = (r.naoCasou ?: []) as List
List semOrigem = (alvos as List).findAll {
ler(it) && !((r.grava ?: [:]) as Map).containsKey(it) &&
!((r.conflitos ?: [:]) as Map).containsKey(it)
}
String detalhe = "${n} código(s) importado(s) de '${bloco.dev}'" +
(conflitosDetalhe ? "; ${conflitosDetalhe.size()} em conflito: ${conflitosDetalhe.join(', ')}" : "") +
(naoCasou ? "; ${naoCasou.size()} sem correspondência: ${naoCasou.join(', ')}" : "") +
(semOrigem ? "; ${semOrigem.size()} armado(s) sem origem no projeto: ${semOrigem.join(', ')}" : "") +
(avisoIp ? " · ${avisoIp}" : "")
logInfo("[IMPORT] ${detalhe}")
sendEvent(name: "importStatus", value: (div || naoCasou || avisoIp) ? "aviso" : "ok")
sendEvent(name: "importDetail", value: detalhe)
sendEvent(name: "divergencias", value: div)
return [importadas: n, divergencias: div, naoCasou: naoCasou, semOrigem: semOrigem]
}
Integer caConferirProjetoIrrf(List alvos, Map ptbr, Closure extra, Closure ler) {
Map bloco
try {
bloco = caCasarDispositivo(caLerArtefato(settings?.projArquivo as String),
settings?.projDispositivo as String)
} catch (Exception e) {
logError("[CONFERIR] ${e.message}")
sendEvent(name: "divergencias", value: -1)
return -1
}
Map r = caResolverImport((bloco.cmds ?: []) as List, alvos, ptbr, extra)
Integer div = 0
List<String> achados = []
String avisoIp = caIpDoProjeto(bloco, false)
if (avisoIp) { div++; achados << avisoIp }
((r.grava ?: [:]) as Map).each { k, v ->
String atual = ler(k) as String
if (!atual) { div++; achados << "'${k}' está no projeto e não no hub" }
else if (atual != v) { div++; achados << "'${k}' MUDOU no projeto — o hub manda o código velho" }
}
((r.conflitos ?: [:]) as Map).each { k, aliases ->
div++
achados << "'${k}' tem apelidos com códigos diferentes no projeto: ${(aliases as List).join(', ')}"
}
((r.naoCasou ?: []) as List).each {
achados << "apelido '${it}' do projeto não casa com nenhuma tecla deste driver"
}
((r.foraDoAlvo ?: []) as List).each {
div++
logError("[CONFERIR] BUG de tabela: '${it}' aponta para uma tecla que este driver não tem. " +
"Reporte — é erro de código, não do seu projeto.")
}
((alvos as List).findAll {
ler(it) && !((r.grava ?: [:]) as Map).containsKey(it) &&
!((r.conflitos ?: [:]) as Map).containsKey(it)
}).each {
achados << "'${it}' está armado no hub sem origem correspondente no projeto"
}
sendEvent(name: "divergencias", value: div)
if (!div && !achados) {
logInfo("[CONFERIR] Hub e arquivo batem para '${bloco.dev}'. ATENÇÃO: compara com o " +
"ARQUIVO, não com a central — se o xConfig mudou e não foi reexportado, isto não enxerga.")
}
achados.each { logWarn("[CONFERIR] ${it}") }
return div
}
import groovy.transform.Field
import groovy.json.JsonOutput
@Field static final Map<String, String> IRWEB_AC = [
"off": "poweroff", "on": "poweron",
"poweroff": "poweroff", "poweron": "poweron", "on/off": "onoff", "i/o": "io",
"auto": "auto", "heat": "heat", "cool": "cool", "fan": "fan", "dry": "dry",
"setautocool": "setautocool",
"fanauto": "fanauto", "fanlow": "fanlow", "fanmed": "fanmed", "fanhigh": "fanhigh",
"fanspeed": "fanspeed",
"temp+": "tempup", "temp-": "tempdown", "^": "up", "v": "down",
"fastcold": "fastcold", "clock": "clock", "sweep": "sweep", "turbo": "turbo",
"swing": "swing", "manual": "manual", "operationmode": "mode", "mode": "mode",
"timer": "timer", "cancel": "cancel", "display": "display",
"comandoextra1": "extra1", "comandoextra2": "extra2", "comandoextra3": "extra3",
"comandoextra4": "extra4", "comandoextra5": "extra5", "comandoextra6": "extra6",
"comandoextra7": "extra7", "comandoextra8": "extra8"
]
@Field static final Map<Integer, String> BTN = [
0: "poweroff", 1: "poweron", 2: "auto", 3: "heat", 4: "cool", 5: "fan",
6: "dry", 7: "setautocool", 8: "extra1", 9: "extra2", 10: "extra3",
11: "extra4", 12: "extra5", 13: "fanauto", 14: "fanlow", 15: "fanmed",
16: "fanhigh", 17: "extra6", 18: "extra7", 19: "extra8", 20: "fastcold",
21: "temp18", 22: "temp20", 23: "temp22", 24: "clock", 25: "sweep",
26: "turbo", 27: "fan", 28: "temp17", 29: "temp23", 30: "temp26",
31: "onoff", 32: "temp19", 33: "temp21", 34: "swing", 35: "manual",
36: "mode", 37: "up", 38: "timer", 39: "cancel", 40: "down",
41: "display", 42: "io", 43: "tempup", 44: "tempdown", 45: "fanspeed",
46: "poweroff", 47: "poweron"
]
@Field static final List<String> ALL_KEYS = [
"poweron", "poweroff", "onoff", "auto", "heat", "cool", "fan", "dry",
"setautocool", "fanauto", "fanlow", "fanmed", "fanhigh", "fanspeed",
"temp16", "temp17", "temp18", "temp19", "temp20", "temp21", "temp22", "temp23",
"temp24", "temp25", "temp26", "temp27", "temp28", "temp29", "temp30",
"tempup", "tempdown", "fastcold", "turbo", "sweep", "swing", "clock",
"timer", "cancel", "display", "io", "manual", "mode", "up", "down",
"extra1", "extra2", "extra3", "extra4", "extra5", "extra6", "extra7", "extra8"
]
@Field static final Map<String, String> PTBR_ALIAS = [
"ligar": "poweron", "liga": "poweron", "acon": "poweron", "acender": "poweron",
"desligar": "poweroff", "desliga": "poweroff", "acoff": "poweroff",
"frio": "cool", "quente": "heat", "ventilar": "fan", "ventilador": "fan",
"seco": "dry", "automatico": "auto",
"temperatura+": "tempup", "temperatura-": "tempdown",
"girar": "swing", "oscilar": "swing", "turbo": "turbo"
]
metadata {
definition(
name: "ControlArt IR AC",
namespace: "tecnosimples",
author: "TecnoSimples"
) {
capability "Thermostat"
capability "Switch" 
capability "RelativeHumidityMeasurement" 
capability "PushableButton"
capability "Actuator"
capability "Initialize"
capability "Refresh"
command "CodigoHEX", [
[name: "Tecla*", type: "ENUM", constraints: ALL_KEYS],
[name: "HEXcode*", type: "STRING", description: "Código sendir completo ou só o payload"]
]
command "GetRemoteDATA"
command "listCodes"
command "clearCodes"
command "sendKey", [[name: "Tecla*", type: "ENUM", constraints: ALL_KEYS]]
command "reconnect"
command "poweron"; command "poweroff"
command "stop"
command "setAmbientTemperature", [[name: "Temperatura* (°C)", type: "NUMBER", description: "Valor do sensor de ambiente"]]
command "setAmbientHumidity", [[name: "Umidade* (%)", type: "NUMBER", description: "Valor do sensor de ambiente"]]
command "verificarLicenca"
command "importarDoProjeto"
command "conferirProjeto"
attribute "boardstatus", "string"
attribute "lastCommandStatus", "string"
attribute "Controle", "string"
attribute "TipoControle", "string" 
attribute "licenca", "string"
attribute "hubUID", "string"
attribute "importStatus", "enum", ["ok", "aviso", "erro"]
attribute "importDetail", "string"
attribute "divergencias", "number"
}
preferences {
section("Conexão (gateway 7Port)") {
input name: "device_IP_address", type: "text", title: "IP do gateway", required: true
input name: "device_port", type: "number", title: "Porta TCP", required: true, defaultValue: 4998
input name: "irChannel", type: "number", title: "Canal IR de saída (1-8)", defaultValue: 1, range: "1..8"
}
section("Import do projeto xConfig (opcional)") {
input name: "projArquivo", type: "text", required: false,
defaultValue: "controlart-xbus.json",
title: "Arquivo de import (File Manager)",
description: "nome padrão do conversor — troque só se você renomeou o arquivo"
input name: "projDispositivo", type: "text", required: false,
title: "Dispositivo no projeto",
description: "nome exato no xConfig. Repetido em vários ambientes? use 'Nome @ Ambiente'"
}
section("Importação ir.molsmart.com.br") {
input name: "webserviceurl", type: "text", title: "URL do controle remoto", required: false
}
section("Saúde da conexão") {
input name: "hbInterval", type: "number", title: "Heartbeat / keep-alive (s) — status offline proativo", defaultValue: 30, range: "5..300", required: false
}
section("Logs") {
input name: "logEnable", type: "bool", title: "Ativar logs de debug (auto-off 30 min)", defaultValue: false
}
}
}
def installed() {
log.info "[AC] Instalado: ${device.displayName}"
}
def updated() { caScheduleLogsOff(); initialize() }
def initialize() {
unschedule()
sendEvent(name: "numberOfButtons", value: 47)
if (!(state.codes instanceof Map)) state.codes = [:]
caPublishSupported()
if (device.currentValue("thermostatMode") == null) sendEvent(name: "thermostatMode", value: "off")
if (device.currentValue("coolingSetpoint") == null) sendEvent(name: "coolingSetpoint", value: 23, unit: "°C")
if (device.currentValue("heatingSetpoint") == null) sendEvent(name: "heatingSetpoint", value: 21, unit: "°C")
if (device.currentValue("thermostatSetpoint") == null) sendEvent(name: "thermostatSetpoint", value: 23, unit: "°C")
if (device.currentValue("switch") == null) sendEvent(name: "switch", value: "off")
caScheduleLogsOff()
caConnect()
}
private void caPublishSupported() {
Map c = (state.codes instanceof Map) ? state.codes : [:]
List<String> modes = []
if (c["poweroff"] || c["onoff"]) modes << "off"
["cool", "heat", "auto", "dry", "fan"].each { if (c[it]) modes << it }
if (!modes) modes = ["off"] 
List<String> fans = []
[fanauto: "auto", fanlow: "low", fanmed: "mid", fanhigh: "high"].each { k, v -> if (c[k]) fans << v }
sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(modes))
sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(fans))
logInfo("[CFG] Modos anunciados: ${modes} · ventilação: ${fans ?: 'nenhuma'}")
}
def uninstalled() { caDisconnect() }
def reconnect() { caConnect() }
def refresh() { logInfo("[AC] Gateway silencioso — estado da conexão via socketStatus/envios.") } 
void caAfterConnect() { caProbeGatewayPoll() } 
String caPollCommand() { return null }
def CodigoHEX(String tecla, String hexcode) {
if (!(state.codes instanceof Map)) state.codes = [:]
state.codes[tecla] = (hexcode ?: "").trim()
logInfo("[CFG] Código da tecla '${tecla}' gravado (${state.codes[tecla].length()} chars)")
}
def clearCodes() { state.codes = [:]; logInfo("[CFG] Todos os códigos IR apagados") }
def listCodes() {
Map codes = (state.codes instanceof Map) ? state.codes : [:]
String resumo = ALL_KEYS.collect { k -> "${k}: ${codes[k] ? 'OK' : '—'}" }.join(" | ")
log.info "${device.displayName}: [CFG] ${resumo}"
}
private String caMapName(String rawName) {
String n = caNormKey(rawName)
if (n ==~ /\d{2}/) {
Integer t = n as Integer
return (t >= 16 && t <= 30) ? "temp${t}" : null
}
return IRWEB_AC[n]
}
private Map caSourceKeys(def data) {
if (data?.commands instanceof Map && data.commands) return data.commands
Map out = [:]
if (data?.functions instanceof List) {
data.functions.each { f -> if (f instanceof Map && f.name != null) out[f.name.toString()] = f.function }
}
return out
}
def GetRemoteDATA() {
String url = (settings.webserviceurl ?: "").trim()
if (!url) { logWarn("[CFG] URL do ir.molsmart não configurada."); return }
try {
httpGet([uri: url, contentType: "application/json", timeout: 15]) { resp ->
def data = resp?.data
Map fonte = caSourceKeys(data)
if (!fonte) {
logWarn("[CFG] Resposta sem nomes de tecla ('commands' ou 'functions[].name') — import ABORTADO. Importar por posição gravaria código na tecla errada.")
return
}
Map novos = [:]
List<String> desconhecidas = []
fonte.each { rawName, code ->
String key = caMapName(rawName.toString())
if (!key) { if (code) desconhecidas << rawName.toString(); return }
String clean = code ? caCleanIrCode(key, code.toString()) : null
if (clean) {
if (novos.containsKey(key)) logWarn("[CFG] '${rawName}' cai em '${key}', que outra tecla deste controle já preencheu — fica a última lida. Confira com listCodes.")
novos[key] = clean
}
}
int gravados = novos.size()
List<String> descartadas = new ArrayList<String>(((state.codes instanceof Map) ? state.codes.keySet() : []) - novos.keySet())
state.codes = novos
if (descartadas) logWarn("[CFG] Teclas antigas descartadas (não existem neste controle): ${descartadas.join(', ')} — se alguma foi colada à mão, recole com CodigoHEX.")
caDropRelativoDuplicado()
sendEvent(name: "Controle", value: data?.name ?: "importado")
sendEvent(name: "TipoControle", value: data?.type ?: "")
caPublishSupported() 
logInfo("[CFG] Controle '${data?.name}' (tipo '${data?.type}') importado POR NOME: ${gravados} teclas de ${fonte.size()} do controle")
if (desconhecidas) logWarn("[CFG] Teclas com código que este driver não conhece (não gravadas): ${desconhecidas.join(', ')}")
}
} catch (Exception e) {
logError("[CFG] Falha ao importar controle: ${e.message}")
}
}
private void caDropRelativoDuplicado() {
String up = state.codes?.get("tempup"), dn = state.codes?.get("tempdown")
if (up && dn && up == dn) {
state.codes.remove("tempup"); state.codes.remove("tempdown")
logWarn("[CFG] 'tempup' e 'tempdown' vieram com o MESMO código — não são passo relativo; descartados. Setpoint usará só temperaturas absolutas.")
}
}
private String caCleanIrCode(String key, String raw) {
String code = (raw ?: "").trim()
if (!code) return null
if (code.startsWith("sendir")) return code
List<String> vals = code.split(",")*.trim()
if (vals.any { !(it ==~ /\d+/) }) {
logWarn("[CFG] Tecla '${key}': código não-numérico do molsmart — descartado.")
return null
}
if ((vals.size() - 3) % 2 != 0) {
logWarn("[CFG] Tecla '${key}': código truncado na fonte (par IR incompleto) — reparado com gap final 768.")
return code + ",768"
}
return code
}
Boolean sendKey(String tecla) {
String code = (state.codes instanceof Map) ? state.codes[tecla] : null
if (!code) {
logWarn("[IR] Tecla '${tecla}' sem código — rode GetRemoteDATA ou CodigoHEX.")
sendEvent(name: "lastCommandStatus", value: "sem código: ${tecla}")
return false
}
String frame = code.startsWith("sendir")
? code
: "sendir,1:${(settings.irChannel ?: 1) as int},1,${code}"
state.waitingAck = true
Boolean ok = caSend(frame)
sendEvent(name: "lastCommandStatus", value: ok ? "enviado: ${tecla}" : "falha de envio: ${tecla}")
if (ok) runIn(6, "ackTimeout")
else state.waitingAck = false
return ok
}
def ackTimeout() {
if (state.waitingAck) {
state.waitingAck = false
sendEvent(name: "lastCommandStatus", value: "sem confirmação (completeir não chegou)")
logWarn("[IR] Gateway não confirmou o último sendir.")
}
if (state.pendingMode) flushPendingMode()
}
def on() {
String last = (state.lastMode ?: "cool") as String
if (last == "off") last = "cool"
setThermostatMode(last)
}
def off() { if (sendKey("poweroff") || sendKey("onoff")) setModeEvents("off", "idle") }
def poweron() { on() }
def poweroff() { off() }
def stop() { off() } 
def auto() { modeViaIR("auto", "auto") }
def cool() { modeViaIR("cool", "cooling") }
def heat() { modeViaIR("heat", "heating") }
def dry() { modeViaIR("dry", "fan only") }
def fan() { modeViaIR("fan", "fan only") }
def emergencyHeat() {
logWarn("[MODE] emergencyHeat não existe em AC IR — aplicando heat") 
heat()
}
def setThermostatMode(String mode) {
switch (mode) {
case "auto": auto(); break
case "cool": cool(); break
case "heat": heat(); break
case "dry": dry(); break
case "fan": fan(); break
case "emergency heat": emergencyHeat(); break
case "off": off(); break
default: logWarn("[MODE] Modo desconhecido: ${mode}")
}
}
private void modeViaIR(String mode, String opState) {
String key = (state.codes?.get(mode)) ? mode : "mode" 
if (key == "mode") logWarn("[MODE] Sem código dedicado para '${mode}' — enviando tecla MODE (cíclica)")
if (device.currentValue("thermostatMode") == "off") {
String pwr = state.codes?.get("poweron") ? "poweron" : (state.codes?.get("onoff") ? "onoff" : null)
if (!pwr) {
logWarn("[MODE] AC off e sem poweron/onoff — não envio modo às cegas (aparelho pode continuar desligado).")
return
}
state.pendingMode = [mode: mode, opState: opState, key: key]
if (sendKey(pwr)) {
runInMillis(800, "flushPendingMode")
} else {
state.pendingMode = null
}
return
}
if (sendKey(key)) setModeEvents(mode, opState)
}
def flushPendingMode() {
Map p = state.pendingMode as Map
if (!p) return
state.pendingMode = null
unschedule("flushPendingMode")
String key = (p.key ?: "mode") as String
if (sendKey(key)) setModeEvents((p.mode as String), (p.opState as String))
}
private void setModeEvents(String mode, String opState) {
if (mode && mode != "off") state.lastMode = mode
sendEvent(name: "thermostatMode", value: mode)
sendEvent(name: "thermostatOperatingState", value: opState)
sendEvent(name: "switch", value: (mode == "off") ? "off" : "on") 
}
def fanAuto() { if (sendKey("fanauto")) sendEvent(name: "thermostatFanMode", value: "auto") }
def fanOn() { fan() } 
def fanCirculate() { fanAuto() } 
def setThermostatFanMode(String fanmode) {
switch (fanmode) {
case "auto": fanAuto(); break
case "circulate": fanCirculate(); break
case "on": fanOn(); break
case "low": if (sendKey("fanlow")) sendEvent(name: "thermostatFanMode", value: "low"); break
case "mid":
case "medium": if (sendKey("fanmed")) sendEvent(name: "thermostatFanMode", value: "mid"); break
case "high": if (sendKey("fanhigh")) sendEvent(name: "thermostatFanMode", value: "high"); break
default: logWarn("[FAN] Modo de ventilação desconhecido: ${fanmode}")
}
}
def setCoolingSetpoint(temperature) { setpoint(temperature, "coolingSetpoint") }
def setHeatingSetpoint(temperature) { setpoint(temperature, "heatingSetpoint") }
private void setpoint(temperature, String attr) {
Integer target
try { target = (temperature as BigDecimal).intValue() } catch (Exception ignored) {
logWarn("[TEMP] Setpoint inválido: ${temperature}"); return
}
if (state.codes?.get("temp${target}")) {
if (sendKey("temp${target}")) setpointEvents(attr, target)
return
}
List<Integer> disp = caTempsDisponiveis()
if (disp) {
Integer perto = disp.min { Math.abs(it - target) }
logWarn("[TEMP] ${target}° não existe neste controle (faixa ${disp.first()}-${disp.last()}°) — enviando ${perto}°.")
if (sendKey("temp${perto}")) setpointEvents(attr, perto)
return
}
Integer atual = (device.currentValue(attr) ?: target) as Integer
if (target == atual) { setpointEvents(attr, target); return }
String stepKey = (target > atual) ? "tempup" : "tempdown"
logWarn("[TEMP] Sem temperatura absoluta importada — enviando 1 passo ${stepKey} (${atual}→${atual + (target > atual ? 1 : -1)}°)")
if (sendKey(stepKey)) setpointEvents(attr, atual + (target > atual ? 1 : -1))
}
def setAmbientTemperature(temperatura) { caSetAmbiente("temperature", temperatura, -50, 80, "°C") }
def setAmbientHumidity(umidade) { caSetAmbiente("humidity", umidade, 0, 100, "%") }
private void caSetAmbiente(String attr, def valor, Number min, Number max, String unidade) {
BigDecimal v
try { v = valor as BigDecimal } catch (Exception ignored) {
logWarn("[AMB] ${attr}: valor inválido ('${valor}') — ignorado."); return
}
if (v < min || v > max) {
logWarn("[AMB] ${attr}: ${v}${unidade} fora da faixa plausível (${min}..${max}) — ignorado."); return
}
sendEvent(name: attr, value: v.setScale(1, java.math.RoundingMode.HALF_UP), unit: unidade)
}
private List<Integer> caTempsDisponiveis() {
Map c = (state.codes instanceof Map) ? state.codes : [:]
return c.keySet().findAll { it ==~ /temp\d+/ }.collect { ((it - "temp") as Integer) }.sort()
}
private void setpointEvents(String attr, Integer value) {
sendEvent(name: attr, value: value, unit: "°C") 
sendEvent(name: "thermostatSetpoint", value: value, unit: "°C")
}
def push(pushed) {
if (pushed == null) { logWarn("[BTN] push nulo ignorado"); return }
Integer n
try { n = pushed as Integer } catch (Exception ignored) { logWarn("[BTN] push inválido: ${pushed}"); return }
sendEvent(name: "pushed", value: n, isStateChange: true)
String key = BTN[n]
if (!key) { logWarn("[BTN] Botão ${n} sem tecla mapeada"); return }
switch (key) {
case "poweron": on(); return
case "poweroff": off(); return
case "auto": case "cool": case "heat": case "dry": case "fan":
setThermostatMode(key); return
case "fanauto": fanAuto(); return
case "fanlow": setThermostatFanMode("low"); return
case "fanmed": setThermostatFanMode("mid"); return
case "fanhigh": setThermostatFanMode("high"); return
default:
if (key.startsWith("temp") && key.length() == 6) {
setpoint(key.substring(4) as Integer, "coolingSetpoint"); return
}
sendKey(key)
}
}
void handleLine(String line) {
if (line.startsWith("completeir")) {
state.waitingAck = false
unschedule("ackTimeout")
sendEvent(name: "lastCommandStatus", value: "confirmado")
if (state.pendingMode) {
unschedule("flushPendingMode")
flushPendingMode()
}
return
}
if (line.toLowerCase().contains("command error")) { 
state.waitingAck = false
unschedule("ackTimeout")
if (state.pendingMode) { 
state.pendingMode = null
unschedule("flushPendingMode")
}
sendEvent(name: "lastCommandStatus", value: "rejeitado pelo gateway (comando malformado)")
logWarn("[IR] Gateway rejeitou o comando (Command ERROR) — código malformado; rode GetRemoteDATA de novo (v1.0.1 repara códigos truncados).")
return
}
if (line.contains("Blaster not found")) { 
state.waitingAck = false
unschedule("ackTimeout")
if (state.pendingMode) { 
state.pendingMode = null
unschedule("flushPendingMode")
}
sendEvent(name: "lastCommandStatus", value: "canal IR inexistente no gateway")
logWarn("[IR] Gateway não tem esse canal IR (Blaster not found) — confira a pref 'Canal IR de saída'; 7Port e xPort aceitam 1..8.")
return
}
logDebug("[RX] Linha não tratada: ${line}")
}
def importarDoProjeto() {
caImportarDoProjeto(
ALL_KEYS, PTBR_ALIAS,
{ n -> caMapName(n) }, 
{ k -> (state.codes instanceof Map) ? state.codes[k] : null },
{ k, v ->
if (!(state.codes instanceof Map)) state.codes = [:]
state.codes[k] = v
})
}
def conferirProjeto() {
caConferirProjetoIrrf(
ALL_KEYS, PTBR_ALIAS,
{ n -> caMapName(n) },
{ k -> (state.codes instanceof Map) ? state.codes[k] : null })
}
