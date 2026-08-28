/**
 * ControlArt xPort Gateway
 *
 * TecnoSimples Tecnologia LTDA
 * contato@tecnosimples.com.br | (14) 99760-6885
 * (c) 2026 TecnoSimples - Todos os direitos reservados.
 *
 * Produto licenciado. Distribuido via Hubitat Package Manager.
 * Uso restrito ao hub licenciado. Ver LICENSE no repositorio.
 * Versao do pacote: 1.0.8 | library embutida: 1.17.0
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
import java.util.concurrent.ConcurrentHashMap
@Field static ConcurrentHashMap<String, Long> GW_BLACKOUT_UNTIL = new ConcurrentHashMap<String, Long>()
@Field static ConcurrentHashMap<String, Long> GW_LAST_SENDMD_AT = new ConcurrentHashMap<String, Long>()
@Field static ConcurrentHashMap<String, Integer> GW_LAST_LEVEL = new ConcurrentHashMap<String, Integer>()
@Field static final String CHILD_SWITCH = "Generic Component Switch"
@Field static final String CHILD_DIMMER = "Generic Component Dimmer"
@Field static final String CHILD_SHADE = "Generic Component Window Shade"
@Field static final Integer CH_PER_MODULE = 3
@Field static final Integer CFG_PULSE_IN = 1 
@Field static final Integer CFG_ONOFF_OUT = 2
@Field static final Integer CFG_DIMMER_OUT = 3
@Field static final Long FOREIGN_THROTTLE_MS = 60000L 
metadata {
definition(
name: "ControlArt xPort Gateway",
namespace: "tecnosimples",
author: "TecnoSimples"
) {
capability "Configuration"
capability "Initialize"
capability "Refresh"
capability "PushableButton" 
capability "Actuator"
command "reconnect"
command "limparOrfaos"
command "relabelFromFile"
command "verificarLicenca"
command "conferirProjeto"
attribute "boardstatus", "string"
attribute "foreignClient", "string" 
attribute "importStatus", "enum", ["ok", "aviso", "cache", "erro"]
attribute "importDetail", "string"
attribute "orphanCount", "number"
attribute "licenca", "string"
attribute "hubUID", "string"
attribute "divergencias", "number"
attribute "feedback", "string"
}
preferences {
section("Conexão (central xPort)") {
input name: "device_IP_address", type: "text", title: "IP do xPort", required: false
input name: "device_port", type: "number", title: "Porta TCP", required: false, defaultValue: 4998
}
section("Módulos xBus") {
input name: "moduleFile", type: "text", required: false,
title: "Arquivo de import (File Manager) — gerado por tools/xconfig2hub.py. " +
"VAZIO = usar a lista manual abaixo. Preenchido, a lista manual é IGNORADA."
input name: "moduleAddresses", type: "text", required: false,
title: "Endereços dos módulos, separados por vírgula — copie do xConfig (ex.: 45-E5-F2, 1A-2B-3C). " +
"TETO DE 255 CARACTERES (~25 endereços): acima disso o hub RECUSA o save inteiro."
}
section("Saúde da conexão") {
input name: "hbInterval", type: "number", title: "Heartbeat / keep-alive (s)", defaultValue: 30, range: "5..300", required: false
}
section("Avançado") {
input name: "permitirRemocao", type: "bool", required: false, defaultValue: false,
title: "Permitir REMOÇÃO de módulos (arma limparOrfaos()) — apagar um filho apaga " +
"as regras do Rule Machine que apontam para ele. Deixe desligado no dia a dia."
input name: "blackoutMs", type: "number", required: false, defaultValue: 5000,
title: "Blackout do resync (ms) — após uma consulta de status, ignora teclas deste módulo " +
"por esse tempo. MÍNIMO 5000: valor menor é rejeitado, não obedecido. " +
"Suba para 10000 em hub muito carregado."
}
section("Logs") {
input name: "logEnable", type: "bool", title: "Ativar logs de debug (auto-off 30 min)", defaultValue: false
}
}
}
def installed() { log.info "[XPORT-GW] Instalado: ${device.displayName}" }
def updated() { caScheduleLogsOff(); initializeCom("failHigh") }
def configure() { initializeCom("failHigh") }
def initialize() { initializeCom("keepCache") }
private void initializeCom(String mode) {
unschedule()
state.caRequiresRx = true
state.typeWarned = [:] 
state.coverOrphanWarned = [:] 
state.coverVerified = [:]
state.bmSampled = [:] 
if (!caParseAddresses(mode)) return 
caAssignBases()
criarCortinasDoArquivo()
publicarFeedbackCortina()
sendEvent(name: "numberOfButtons", value: caNumberOfButtons())
sendEvent(name: "foreignClient", value: "nenhum")
logButtonMap()
pruneOrphans()
caScheduleLogsOff()
caConnect()
}
def uninstalled() { caDisconnect() }
def reconnect() { caConnect() }
def refresh() { askConfigAll() }
void caAfterConnect() { caProbeGatewayPoll(); runInMillis(300, "askConfigAll") }
String caPollCommand() { return null }
private Boolean caParseAddresses(String mode) {
String arquivo = ((settings.moduleFile ?: "") as String).trim()
return arquivo ? parseDoArquivo(arquivo, mode) : parseDaPreferencia()
}
private Map<String, List<String>> triarEnderecos(String csv) {
List<String> ok = [], bad = []
(csv ?: "").split(",").each { String part ->
String p = part.trim().toUpperCase()
if (!p) return
if (p ==~ /^[0-9A-F]{2}-[0-9A-F]{2}-[0-9A-F]{2}$/) { ok << p } else { bad << p }
}
return [ok: ok, bad: bad]
}
private void desarmarRemocao(String motivo) {
if (settings.permitirRemocao != true) return
device.updateSetting("permitirRemocao", [value: false, type: "bool"])
logWarn("[LIMPEZA] 'Permitir REMOÇÃO' DESARMADO automaticamente (${motivo}). " +
"Ligue de novo se precisar remover outra vez.")
}
private void setImportStatus(String st, String detalhe, Integer orfaos) {
sendEvent(name: "importStatus", value: st)
sendEvent(name: "importDetail", value: detalhe)
if (orfaos != null) sendEvent(name: "orphanCount", value: orfaos)
}
private Boolean parseDaPreferencia() {
Map triado = triarEnderecos(settings.moduleAddresses as String)
List<String> ok = triado.ok, bad = triado.bad
if (((state.lastImportFile ?: "") as String)) {
List<String> atuais = (state.addrs instanceof List) ? (state.addrs as List) : []
List<String> perdidos = atuais.findAll { !ok.contains(it) }
if (perdidos && settings.permitirRemocao != true) {
String msg = "Sair do modo arquivo derrubaria ${perdidos.size()} endereço(s) que " +
"não estão na lista manual (${perdidos.join(', ')}) — e a lista manual " +
"tem teto de 255 caracteres. NENHUM filho foi removido. Para sair mesmo " +
"assim, acrescente esses endereços à lista manual, OU ligue 'Permitir " +
"REMOÇÃO' e salve de novo — aí eles e os filhos deles são apagados."
logError("[IMPORT] ${msg}")
setImportStatus("erro", msg, null)
caDisconnect()
caSetBoardStatus("offline")
return false
}
if (perdidos) desarmarRemocao("saída do modo arquivo removeu ${perdidos.size()} endereço(s)")
state.lastImportFile = ""
state.labels = [:]
}
if (bad) logWarn("[ADDR] Ignorados (use o formato AA-BB-CC): ${bad.join(', ')}")
if (!ok) {
logError("[ADDR] Nenhum endereço válido — copie os endereços do xConfig.")
setImportStatus("erro", "nenhum endereço válido na lista manual", null)
state.addrs = []
caDisconnect()
caSetBoardStatus("offline")
return false
}
state.addrs = ok
logInfo("[ADDR] ${ok.size()} módulo(s): ${ok.join(', ')}")
setImportStatus("ok", "modo manual — ${ok.size()} endereço(s)", 0)
return true
}
private Map lerArtefato(String nome) {
byte[] b
try {
b = downloadHubFile(nome)
} catch (Exception e) {
throw new IllegalStateException("arquivo '${nome}' não encontrado ou ilegível no File Manager")
}
if (b == null) throw new IllegalStateException("arquivo '${nome}' voltou vazio do File Manager")
Map art
try {
art = (Map) parseJson(new String(b))
} catch (Exception e) {
throw new IllegalStateException("'${nome}' não é JSON válido — regere pelo tools/xconfig2hub.py")
}
if (!(art?.v)) throw new IllegalStateException("'${nome}' não parece um arquivo de import (sem 'v')")
Integer v = (art.v as Integer)
if (v < 1 || v > 3) throw new IllegalStateException(
"'${nome}' é da versão ${art.v}; este driver lê da 1 até a 3 — atualize o driver ou regere o arquivo")
if (!(art.gateways instanceof List) || !art.gateways) throw new IllegalStateException(
"'${nome}' não tem nenhuma central em 'gateways'")
return art
}
private Map escolherBloco(Map art, String nome) {
List gws = ((art.gateways ?: []) as List).findAll {
(it.modules instanceof List) && (it.modules as List)
}
if (!gws) throw new IllegalStateException(
"'${nome}' não tem nenhuma central com módulos — só blocos de IR/RF")
List<String> ips = gws.collect { "${((it.ip ?: '') as String).trim()}:${it.port ?: '?'}" }
String meu = ((settings.device_IP_address ?: "") as String).trim()
if (!meu) {
if (gws.size() == 1) return (Map) gws[0]
throw new IllegalStateException(
"preencha o IP da central deste device — '${nome}' tem ${gws.size()}: ${ips.join(', ')}")
}
List casam = gws.findAll { (((it.ip ?: "") as String).trim()) == meu }
if (casam.size() == 1) return (Map) casam[0]
if (!casam) throw new IllegalStateException(
"nenhuma central com IP ${meu} em '${nome}' — o arquivo tem: ${ips.join(', ')}")
throw new IllegalStateException(
"'${nome}' tem ${casam.size()} centrais com o MESMO IP ${meu} — arquivo malformado")
}
private List<String> enderecosDoBloco(Map bloco, String nome) {
List mods = (bloco.modules instanceof List) ? (bloco.modules as List) : []
Map triado = triarEnderecos(mods.collect { ((it.addr ?: "") as String) }.join(","))
if (triado.bad) throw new IllegalStateException(
"endereço fora do formato AA-BB-CC em '${nome}': ${triado.bad.join(', ')}")
return triado.ok
}
private Boolean parseDoArquivo(String nome, String mode) {
List<String> atuais = (state.addrs instanceof List) ? (state.addrs as List) : []
Map bloco
List<String> doArquivo
try {
bloco = escolherBloco(lerArtefato(nome), nome) 
doArquivo = enderecosDoBloco(bloco, nome)
if (!doArquivo && (atuais || getChildDevices())) throw new IllegalStateException(
"'${nome}' não tem nenhum módulo para esta central, mas o device já tem " +
"${atuais.size()} endereço(s) e ${getChildDevices().size()} filho(s)")
} catch (Exception e) {
String motivo = e.message ?: e.toString()
if (mode == "failHigh") {
logError("[IMPORT] ${motivo}")
setImportStatus("erro", motivo, null)
caDisconnect()
caSetBoardStatus("offline")
return false 
}
logError("[IMPORT] ${motivo} — mantendo a lista anterior (${atuais.size()} módulos)")
setImportStatus("cache", "${motivo} — usando lista anterior de ${atuais.size()} módulo(s)", null)
return !atuais.isEmpty()
}
List<String> nova = new ArrayList<String>(atuais)
doArquivo.each { if (!nova.contains(it)) nova << it }
List<String> orfaos = atuais.findAll { !doArquivo.contains(it) }
state.addrs = nova
state.lastImportFile = nome
Map labels = (state.labels instanceof Map) ? new HashMap(state.labels as Map) : [:]
((bloco.modules ?: []) as List).each { Map m ->
String a = ((m.addr ?: "") as String).trim().toUpperCase()
if (a) labels[a] = [room: (m.room ?: "") as String,
kind: (m.kind ?: "light") as String,
ch: (m.ch instanceof List) ? m.ch : []]
}
state.labels = labels
String meu = ((settings.device_IP_address ?: "") as String).trim()
String doArq = ((bloco.ip ?: "") as String).trim()
String aviso = null
if (!meu && doArq) {
device.updateSetting("device_IP_address", [value: doArq, type: "text"])
if (bloco.port) device.updateSetting("device_port", [value: (bloco.port as Integer), type: "number"])
logInfo("[IMPORT] IP preenchido pelo arquivo: ${doArq}:${bloco.port ?: 4998}")
} else if (meu && doArq && meu != doArq) {
aviso = "o arquivo diz ${doArq}, este device está em ${meu} — confira qual é o certo"
logWarn("[IMPORT] ${aviso}")
}
if (orfaos) {
logError("[IMPORT] ${orfaos.size()} endereço(s) NÃO estão em '${nome}' " +
"(${orfaos.join(', ')}): nenhum filho foi removido. " +
"Rode limparOrfaos() se a remoção for intencional.")
}
logInfo("[IMPORT] ${doArquivo.size()} módulo(s) de '${nome}'; total ativo ${nova.size()}")
Integer divCenas = aplicarCenas((bloco.scenes ?: []) as List)
sendEvent(name: "divergencias", value: divCenas)
String detalhe = "${doArquivo.size()} módulo(s) de ${nome}"
if (orfaos) detalhe += " · ${orfaos.size()} órfão(s): ${orfaos.join(', ')}"
if (aviso) detalhe += " · ${aviso}"
setImportStatus((orfaos || aviso) ? "aviso" : "ok", detalhe, orfaos.size())
return !nova.isEmpty()
}
private String hexToDec(String hex) {
return hex.split("-").collect { Integer.parseInt(it, 16) as String }.join(",")
}
private void caAssignBases() {
Map bases = (state.btnBase instanceof Map) ? new HashMap(state.btnBase) : [:]
(state.addrs as List).each { String hex ->
if (!bases.containsKey(hex)) {
int nxt = bases.isEmpty() ? 0 : ((bases.values().collect { it as int }.max()) + CH_PER_MODULE)
bases[hex] = nxt
}
}
state.btnBase = bases
}
private int caNumberOfButtons() {
List<Integer> act = (state.addrs as List).findAll { state.btnBase[it] != null }
.collect { state.btnBase[it] as int }
return act ? (act.max() + CH_PER_MODULE) : 0
}
private void logButtonMap() {
Map<String, String> agora = [:]
(state.addrs as List).each { agora[it as String] = "${state.btnBase[it]}" }
Map<String, String> antes = [:]
((state.btnMapSig ?: "") as String).tokenize(" ").each { String par ->
int i = par.lastIndexOf(':') 
if (i > 0) antes[par.substring(0, i)] = par.substring(i + 1)
}
List<String> chavesAntes = new ArrayList<String>(antes.keySet())
List<String> chavesAgora = new ArrayList<String>(agora.keySet())
List<String> renumerados = chavesAntes.findAll { agora.containsKey(it) && agora[it] != antes[it] }
List<String> removidos = chavesAntes.findAll { !agora.containsKey(it) }
List<String> novos = chavesAgora.findAll { !antes.containsKey(it) }
if (renumerados) { 
logWarn("[BOTÕES] RENUMERADOS — confira as regras que usam estes botões: " +
renumerados.collect { "${it}: base ${antes[it]} → ${agora[it]}" }.join(', '))
}
if (removidos) { 
logWarn("[BOTÕES] Endereço(s) fora da lista: ${removidos.join(', ')} — " +
"os botões deles não serão mais emitidos. A base fica reservada: readicionar devolve a faixa.")
}
if (novos && antes) logInfo("[BOTÕES] Endereço(s) novo(s): ${novos.join(', ')}")
state.btnMapSig = (state.addrs as List).collect { "${it}:${state.btnBase[it]}" }.sort().join(" ")
(state.addrs as List).each { String hex ->
int b = state.btnBase[hex] as int
logInfo("[BOTÕES] ${hex} → S1=${b + 1}  S2=${b + 2}  S3=${b + 3}")
}
}
void handleLine(String line) {
if (line.startsWith("setmd,")) { onSetmd(line); return }
if (line.startsWith("setconfigmd,")) { onSetconfigmd(line); return }
if (line.startsWith("setbmmd,")) { onSetbmmd(line); return }
logDebug("[RX] Linha não tratada: ${line}")
}
private void onSetbmmd(String line) {
String[] f = line.split(",", -1)
if (f.size() != 7) { logDebug("[RX] setbmmd com ${f.size()} campos — ignorado"); return }
String hex = f[1]?.trim()?.toUpperCase()
if (!(state.addrs as List)?.contains(hex)) {
logInfo("[RX] setbmmd de ${hex} — módulo não cadastrado aqui, ignorado")
return
}
if (kindDe(hex) != "cover") {
logError("[CRÍTICO] ${hex}: o arquivo NÃO diz cortina, mas o módulo responde " +
"como motor (setbmmd). Confira o projeto no xConfig.")
return
}
Map vistos = (state.bmSampled instanceof Map) ? new HashMap(state.bmSampled as Map) : [:]
if (!vistos[hex]) {
vistos[hex] = true
state.bmSampled = vistos
logInfo("[CORTINA] ${hex} confirmada pelo módulo (setbmmd). Campos observados: " +
"${f[2]},${f[3]},${f[4]},${f[5]},${f[6]} — semântica ainda NÃO medida, " +
"o driver não deriva posição nem tecla deles.")
}
Map ver = (state.coverVerified instanceof Map) ? new HashMap(state.coverVerified as Map) : [:]
if (ver[hex] == true) return 
ver[hex] = true
state.coverVerified = ver
publicarFeedbackCortina()
}
private void onSetmd(String line) {
String[] f = line.split(",", -1)
if (f.size() != 8) { logDebug("[RX] setmd com ${f.size()} campos — ignorado"); return }
String hex = f[1]?.trim()?.toUpperCase()
if (!(state.addrs as List)?.contains(hex)) {
logInfo("[RX] setmd de ${hex} — módulo não cadastrado aqui, ignorado")
return
}
for (int k = 2; k <= 7; k++) {
if (!(f[k]?.trim() ==~ /^\d{1,5}$/)) {
logDebug("[RX] setmd com campo não numérico (${f[k]}) — frame descartado")
return
}
}
long until = (GW_BLACKOUT_UNTIL[gwKey(hex)] ?: 0L) as long
boolean muted = now() < until
if (!muted && ((f[2].trim() as int) == 0) && ((f[3].trim() as int) == 0) && ((f[4].trim() as int) == 0)) {
long lastCmd = (GW_LAST_SENDMD_AT[gwKey(hex)] ?: 0L) as long
if ((now() - lastCmd) > caBlackoutMs()) {
String txt = "Frame de status de ${hex} que este driver não pediu — possível outro cliente " +
"na xPort, xConfig aberto, ou device ControlArt Xbus-Relay/Dimmer legado no mesmo " +
"endereço. Não é erro por si só; é a pista se aparecer tecla fantasma."
sendEvent(name: "foreignClient", value: "suspeita", descriptionText: txt)
long lastWarn = (state.foreignWarnAt ?: 0L) as long
if ((now() - lastWarn) > FOREIGN_THROTTLE_MS) { 
state.foreignWarnAt = now()
logWarn("[FOREIGN] ${txt}")
}
}
}
int base = (state.btnBase[hex] ?: 0) as int
for (int i = 0; i < CH_PER_MODULE; i++) {
int v = f[2 + i].trim() as int
if (v != 1) continue
if (muted) {
logDebug("[BLACKOUT] ${hex} IN${i + 1}=1 dentro da janela de resync — eco do latch, não é toque")
continue
}
int btn = base + i + 1
sendEvent(name: "pushed", value: btn, isStateChange: true, type: "physical",
descriptionText: "${device.displayName} tecla S${i + 1} do módulo ${hex} (botão ${btn})")
}
for (int j = 0; j < CH_PER_MODULE; j++) {
def cd = getChildDevice(outDni(hex, j))
if (!cd) continue
int o = f[5 + j].trim() as int
String sw = (o == 0) ? "off" : "on"
List evts = []
if (cd.currentValue("switch") != sw) {
evts << [name: "switch", value: sw, descriptionText: "${cd.displayName} ${sw}"]
}
if (o > 0 && cd.hasCapability("SwitchLevel")) {
int lvl = rawToLevel(o)
GW_LAST_LEVEL[gwChKey(hex, j)] = lvl
if (((cd.currentValue("level") ?: -1) as Integer) != lvl) {
evts << [name: "level", value: lvl, descriptionText: "${cd.displayName} level ${lvl}%"]
}
}
if (evts) { cd.parse(evts) }
}
}
private String outDni(String hex, int ch) {
return "${device.id}-${hex.replace('-', '')}-${ch}"
}
private String coverDni(String hex) { return "${device.id}-${hex.replace('-', '')}-COVER" }
private String coverHexOf(String dni) {
def m = ((dni ?: "") as String) =~ /^\d+-([0-9A-F]{6})-COVER$/
if (!m.find()) return null
String h = m.group(1)
return "${h[0..1]}-${h[2..3]}-${h[4..5]}"
}
private String rotuloCortina(String hex) {
Map todos = (state.labels instanceof Map) ? (state.labels as Map) : [:]
Map l = (todos[hex] instanceof Map) ? (todos[hex] as Map) : null
if (!l) return null
List chs = (l.ch instanceof List) ? (l.ch as List) : []
String alias = (chs && chs[0]) ? ((chs[0] ?: "") as String).trim() : ""
String room = ((l.room ?: "") as String).trim()
if (!alias && !room) return null
if (!alias) return "${room} cortina"
return room ? "${room} — ${alias}" : alias
}
private String kindDe(String hex) {
Map l = (state.labels instanceof Map) ? (state.labels[hex] as Map) : null
return (l?.kind ?: "light") as String
}
def askConfigAll() {
state.cfgSeen = []
List addrs = (state.addrs as List) ?: []
addrs.eachWithIndex { String hex, int i ->
runInMillis(150 * i, "askConfigOne", [data: [hex: hex], overwrite: false])
}
int off = 150 * addrs.size() + 500
addrs.eachWithIndex { String hex, int i ->
runInMillis(off + 150 * i, "askStatusOne", [data: [hex: hex], overwrite: false])
}
int warnSec = Math.max(5, (int) Math.ceil((150.0 * addrs.size() + 5000.0) / 1000.0))
runIn(warnSec, "warnSilentModules")
}
def askConfigOne(Map data) {
caSend("mdcmd_getconfigmd,${hexToDec(data.hex as String)}")
}
def warnSilentModules() {
List<String> seen = (state.cfgSeen instanceof List) ? state.cfgSeen : []
Map ver = (state.coverVerified instanceof Map) ? (state.coverVerified as Map) : [:]
(state.addrs as List).each { String hex ->
if (ver[hex] == true) return
if (!seen.contains(hex)) {
String cauda = (kindDe(hex) == "cover")
? "O filho de CORTINA existe (nasceu do arquivo) e está visível, mas fica " +
"'nao-verificado': todo comando nele é RECUSADO até o módulo responder."
: "Nenhum filho foi criado para ele."
logWarn("[ADDR] Módulo ${hex} não respondeu ao getconfigmd — confira o endereço no xConfig, " +
"OU a central pode estar no teto de sessões TCP (a 11ª conecta e fica muda). " +
cauda)
}
}
}
private void onSetconfigmd(String line) {
String[] f = line.split(",", -1)
if (f.size() != 8) { logDebug("[RX] setconfigmd com ${f.size()} campos — ignorado"); return }
String hex = f[1]?.trim()?.toUpperCase()
if (!(state.addrs as List)?.contains(hex)) {
logInfo("[RX] setconfigmd de ${hex} — não cadastrado aqui, ignorado")
return
}
List<String> seen = (state.cfgSeen instanceof List) ? new ArrayList(state.cfgSeen) : []
if (!seen.contains(hex)) { seen << hex }
state.cfgSeen = seen
for (int k = 2; k <= 7; k++) {
if (!(f[k]?.trim() ==~ /^\d{1,5}$/)) {
logDebug("[RX] setconfigmd com campo não numérico (${f[k]}) — frame descartado")
return
}
}
if (kindDe(hex) == "cover") {
Boolean tipoConhecido = false
for (int j = 0; j < CH_PER_MODULE; j++) {
Integer t = null
try { t = (f[5 + j]?.trim() as Integer) } catch (Exception ignored) { }
if (t == CFG_ONOFF_OUT || t == CFG_DIMMER_OUT) tipoConhecido = true
}
Map ver = (state.coverVerified instanceof Map) ? new HashMap(state.coverVerified as Map) : [:]
if (tipoConhecido) {
ver[hex] = false
logError("[CRÍTICO] ${hex}: o arquivo diz que é cortina, mas o módulo " +
"reporta canal de relé/dimmer. NÃO corrijo sozinho — confira o " +
"projeto no xConfig. Comandos nesta cortina serão RECUSADOS.")
state.coverVerified = ver
publicarFeedbackCortina()
} else {
ver[hex] = true
logInfo("[CHILD] ${hex}: cortina confirmada pelo módulo (tipo de canal " +
"desconhecido, como esperado) — código observado: ${f[5]},${f[6]},${f[7]}")
def cd = getChildDevice(coverDni(hex))
if (!cd) {
String rotulo = rotuloCortina(hex) ?: hex
addChildDevice("hubitat", CHILD_SHADE, coverDni(hex),
[name: "${hex} cortina", isComponent: true, label: rotulo])
logInfo("[CHILD] Criado ${coverDni(hex)} (cortina) — ${rotulo}")
}
state.coverVerified = ver
publicarFeedbackCortina()
return 
}
}
if (kindDe(hex) != "cover" && getChildDevice(coverDni(hex))) {
coverOrphanWarn(hex)
} else {
clearCoverOrphanWarn(hex)
}
List tipos = []
for (int j = 0; j < CH_PER_MODULE; j++) {
Integer tipo = null
try { tipo = (f[5 + j]?.trim() as Integer) } catch (Exception ignored) { }
tipos << tipo
String dni = outDni(hex, j)
String esperado = (tipo == CFG_ONOFF_OUT) ? CHILD_SWITCH :
(tipo == CFG_DIMMER_OUT) ? CHILD_DIMMER : null
if (!esperado) continue
def cd = getChildDevice(dni)
if (cd && cd.typeName != esperado) { typeMismatchWarn(dni, cd.typeName as String, esperado); continue }
if (cd) { clearTypeWarn(dni); continue }
String rotulo = rotuloDe(hex, j)
Map props = [name: "${hex} saída ${j + 1}", isComponent: true]
if (rotulo) props.label = rotulo
addChildDevice("hubitat", esperado, dni, props)
logInfo("[CHILD] Criado ${dni} (${esperado == CHILD_DIMMER ? 'dimmer' : 'relé'})${rotulo ? " — ${rotulo}" : ''}")
}
Map ct = (state.chTypes instanceof Map) ? new HashMap(state.chTypes as Map) : [:]
ct[hex] = tipos
state.chTypes = ct
}
private String rotuloDe(String hex, int ch) {
Map todos = (state.labels instanceof Map) ? (state.labels as Map) : [:]
Map l = (todos[hex] instanceof Map) ? (todos[hex] as Map) : null
if (!l) return null
List chs = (l.ch instanceof List) ? (l.ch as List) : []
String alias = (ch < chs.size()) ? ((chs[ch] ?: "") as String).trim() : ""
String room = ((l.room ?: "") as String).trim()
if (!alias && !room) return null
if (!alias) return "${room} saída ${ch + 1}"
return room ? "${room} — ${alias}" : alias
}
private void typeMismatchWarn(String dni, String atual, String esperado) {
Map warned = (state.typeWarned instanceof Map) ? new HashMap(state.typeWarned as Map) : [:]
if (warned[dni]) return
warned[dni] = true
state.typeWarned = warned
logWarn("[CHILD] ${dni} é '${atual}' mas o canal agora é '${esperado}' (tipo mudou no xConfig). " +
"NÃO recrio sozinho. Remova o filho manualmente e clique Initialize. " +
"Comandos neste filho serão RECUSADOS enquanto o tipo não bater.")
}
private void clearTypeWarn(String dni) {
if (!(state.typeWarned instanceof Map) || !state.typeWarned[dni]) return
Map warned = new HashMap(state.typeWarned as Map)
warned.remove(dni)
state.typeWarned = warned
}
private void coverOrphanWarn(String hex) {
String dni = coverDni(hex)
Map warned = (state.coverOrphanWarned instanceof Map) ? new HashMap(state.coverOrphanWarned as Map) : [:]
if (warned[dni]) return
warned[dni] = true
state.coverOrphanWarned = warned
logWarn("[CHILD] ${hex}: filho de cortina remanescente (${dni}) — o arquivo não " +
"marca mais este endereço como cortina. NÃO removo sozinho: apague manualmente se " +
"a mudança for intencional.")
}
private void clearCoverOrphanWarn(String hex) {
String dni = coverDni(hex)
if (!(state.coverOrphanWarned instanceof Map) || !state.coverOrphanWarned[dni]) return
Map warned = new HashMap(state.coverOrphanWarned as Map)
warned.remove(dni)
state.coverOrphanWarned = warned
}
def relabelFromFile() {
String nome = ((settings.moduleFile ?: "") as String).trim()
if (!nome) {
logError("[ROTULO] Nenhum arquivo de import configurado.")
return
}
try {
Map bloco = escolherBloco(lerArtefato(nome), nome)
Map labels = [:]
((bloco.modules ?: []) as List).each { Map m ->
String a = ((m.addr ?: "") as String).trim().toUpperCase()
if (a) labels[a] = [room: (m.room ?: "") as String,
kind: (m.kind ?: "light") as String,
ch: (m.ch instanceof List) ? m.ch : []]
}
state.labels = labels
int n = 0
getChildDevices().each { cd ->
Map p = dniParts(cd.deviceNetworkId)
if (p == null) return
String r = rotuloDe(p.hex as String, p.ch as Integer)
if (r) { cd.setLabel(r); n++; logInfo("[ROTULO] ${cd.deviceNetworkId} → ${r}") }
}
logInfo("[ROTULO] ${n} filho(s) renomeado(s) a partir de '${nome}'.")
setImportStatus("ok", "${n} filho(s) renomeado(s) de ${nome}", null)
} catch (Exception e) {
String motivo = e.message ?: e.toString()
logError("[ROTULO] ${motivo}")
setImportStatus("erro", motivo, null)
}
}
def limparOrfaos() {
if (settings.permitirRemocao != true) {
logWarn("[LIMPEZA] DESARMADO — ligue 'Permitir REMOÇÃO' nas preferências. " +
"Nada foi apagado. (Comando de driver é chamável por Rule Machine, " +
"app e Maker API; este apaga device.)")
return
}
String nome = ((settings.moduleFile ?: "") as String).trim()
if (!nome) {
logError("[LIMPEZA] Sem arquivo de import — no modo manual a poda já acontece ao salvar.")
return
}
state.caIntentionalClose = true 
unschedule() 
caDisconnect()
List<String> listaFinal = (state.addrs instanceof List) ? new ArrayList<String>(state.addrs as List) : []
try {
Map art = lerArtefato(nome) 
Map bloco = escolherBloco(art, nome)
List<String> doArquivo = enderecosDoBloco(bloco, nome)
List<String> orfaos = listaFinal.findAll { !doArquivo.contains(it) }
Integer versaoArquivo = (art.v as Integer)
Map nomes = (state.sceneNames instanceof Map) ? new HashMap(state.sceneNames as Map) : [:]
List<String> cenasMortas = []
if (versaoArquivo < 2) {
logInfo("[LIMPEZA] '${nome}' é v${versaoArquivo} — não carrega cena; nenhuma cena foi conferida ou removida.")
} else {
Map doArq = [:]
((bloco.scenes ?: []) as List).each { c -> if (c?.id) doArq[(c.id as Integer).toString()] = true }
cenasMortas = nomes.keySet()
.findAll { !doArq.containsKey(it as String) }.collect { it as String }
}
if (!orfaos && !cenasMortas) {
logInfo("[LIMPEZA] Nenhum órfão — a lista já bate com '${nome}'.")
setImportStatus("ok", "sem órfãos", 0)
return
}
if (orfaos) {
List<String> prefixos = orfaos.collect { "${device.id}-${it.replace('-', '')}-" }
List doomed = getChildDevices().findAll { cd -> prefixos.any { cd.deviceNetworkId.startsWith(it) } }
logWarn("[LIMPEZA] Removendo ${orfaos.size()} endereço(s): ${orfaos.join(', ')}")
orfaos.each { a -> for (int c = 0; c < CH_PER_MODULE; c++) { GW_LAST_LEVEL.remove(gwChKey(a as String, c)) } }
logWarn("[LIMPEZA] ${doomed.size()} filho(s) serão APAGADOS: " +
"${doomed.collect { it.displayName }.join(', ')}")
listaFinal = listaFinal.findAll { !orfaos.contains(it) }
state.addrs = listaFinal
pruneOrphans() 
}
List<String> cenasMortasNomeadas = cenasMortas.collect { "${it} (${nomes[it]})" }
if (cenasMortas) {
logWarn("[LIMPEZA] Removendo ${cenasMortas.size()} cena(s) que sumiram do projeto: ${cenasMortasNomeadas.join(', ')}")
cenasMortas.each { k ->
String dni = sceneDni(k as Integer)
if (getChildDevice(dni)) deleteChildDevice(dni)
nomes.remove(k)
}
state.sceneNames = nomes
}
List<String> feito = []
if (orfaos) feito << "removidos ${orfaos.size()} endereço(s): ${orfaos.join(', ')}"
if (cenasMortas) feito << "removida(s) ${cenasMortas.size()} cena(s): ${cenasMortasNomeadas.join(', ')}"
logInfo("[LIMPEZA] Concluído. ${listaFinal.size()} endereço(s) ativos.")
setImportStatus("ok", feito.join(" | "), 0)
desarmarRemocao("limpeza concluída")
} catch (Exception e) {
String motivo = e.message ?: e.toString()
logError("[LIMPEZA] Abortado sem apagar nada: ${motivo}")
setImportStatus("erro", "limpeza abortada: ${motivo}", null)
} finally {
state.addrs = listaFinal 
state.caIntentionalClose = false
caConnect() 
}
}
private void pruneOrphans() {
List<String> prefixos = (state.addrs as List).collect { "${device.id}-${it.replace('-', '')}-" }
getChildDevices().each { cd ->
if (isSceneDni(cd.deviceNetworkId as String)) return
if (!prefixos.any { cd.deviceNetworkId.startsWith(it) }) {
logInfo("[CHILD] Removendo órfão ${cd.deviceNetworkId}")
Map p = dniParts(cd.deviceNetworkId)
if (p) { GW_LAST_LEVEL.remove(gwChKey(p.hex as String, p.ch as int)) }
deleteChildDevice(cd.deviceNetworkId)
}
}
}
private long caBlackoutMs() {
return Math.max(5000L, Math.min(10000L, ((settings?.blackoutMs ?: 5000) as long)))
}
private void askStatusMuted(String hex) {
GW_BLACKOUT_UNTIL[gwKey(hex)] = now() + caBlackoutMs()
caSend("mdcmd_getmd,${hexToDec(hex)}")
}
private String gwKey(String hex) { return "${device.id}:${hex}" }
private static int levelToRaw(int level) {
int l = Math.max(0, Math.min(100, level))
return (int) Math.round(l * 255.0d / 100.0d)
}
private static int rawToLevel(int raw) {
return Math.max(1, (int) Math.round(raw * 100.0d / 255.0d))
}
private String gwChKey(String hex, int ch) { return "${device.id}:${hex}:${ch}" }
def askStatusOne(Map data) { askStatusMuted(data.hex as String) }
private String sceneDni(Integer id) { return "${device.id}-SCENE-${id}" }
private Boolean isSceneDni(String dni) {
return ((dni ?: "") as String).startsWith("${device.id}-SCENE-")
}
private Integer sceneIdOf(String dni) {
def m = ((dni ?: "") =~ /-SCENE-(\d+)$/)
return m.find() ? (m.group(1) as Integer) : null
}
private Integer aplicarCenas(List cenas) {
Integer div = 0
Map nomes = (state.sceneNames instanceof Map) ? new HashMap(state.sceneNames as Map) : [:]
((cenas ?: []) as List).each { c ->
Integer id = 0
try { id = (c?.id ?: 0) as Integer } catch (Exception ignored) { id = 0 }
String nome = ((c?.name ?: "") as String).trim()
if (!id || !nome) {
logWarn("[CENA] Entrada sem id ou sem nome no arquivo — ignorada: ${c}")
return
}
String room = ((c?.room ?: "") as String).trim()
String rotulo = room ? "${room} — ${nome}" : nome
String dni = sceneDni(id)
String chave = id.toString() 
def cd = getChildDevice(dni)
if (!cd) {
addChildDevice("hubitat", CHILD_SWITCH, dni,
[name: "Cena ${id}", label: rotulo, isComponent: true])
logInfo("[CENA] Criada ${dni} — ${rotulo}")
} else {
String antes = (nomes[chave] ?: "") as String
if (antes && antes != nome) {
div++
logWarn("[CENA] A cena ${id} mudou de nome no projeto: '${antes}' → '${nome}'. " +
"Se você só renomeou, ignore. Se o projeto foi RECRIADO, o id ${id} " +
"pode ser OUTRA cena — revise as regras que usam este botão.")
}
if (cd.label != rotulo) {
cd.setLabel(rotulo)
logInfo("[CENA] ${dni} → ${rotulo}")
}
}
nomes[chave] = nome
}
state.sceneNames = nomes
return div
}
private void dispararCena(cd) {
Integer id = sceneIdOf(cd.deviceNetworkId as String)
if (!id) { logError("[CENA] DNI inesperado: ${cd.deviceNetworkId}"); return }
if (!caSend("sendScene,${id}")) return
def filho = getChildDevice(cd.deviceNetworkId as String)
if (!filho) { logError("[CENA] filho ${cd.deviceNetworkId} sumiu entre o disparo e o estado"); return }
filho.parse([[name: "switch", value: "on", descriptionText: "cena ${id} disparada"]])
runInMillis(800, "desligarCena", [data: [dni: cd.deviceNetworkId as String], overwrite: false])
}
void desligarCena(Map data) {
def cd = getChildDevice(data?.dni as String)
if (cd) cd.parse([[name: "switch", value: "off", descriptionText: "cena é momentânea"]])
}
def conferirProjeto() {
String nome = ((settings.moduleFile ?: "") as String).trim()
if (!nome) {
logWarn("[CONFERIR] Sem 'Arquivo de import' configurado — nada a comparar.")
sendEvent(name: "divergencias", value: -1)
return
}
Integer div = 0
List<String> achados = []
try {
Map art = lerArtefato(nome)
Map bloco = escolherBloco(art, nome)
List<String> doArquivo = enderecosDoBloco(bloco, nome)
List<String> atuais = (state.addrs instanceof List) ? (state.addrs as List) : []
atuais.findAll { !doArquivo.contains(it) }.each {
div++; achados << "módulo ${it} está no hub e NÃO no arquivo (rode limparOrfaos se for intencional)"
}
doArquivo.findAll { !atuais.contains(it) }.each {
div++; achados << "módulo ${it} está no arquivo e não no hub — salve o device para importar"
}
Integer versaoArquivo = (art.v as Integer)
if (versaoArquivo < 2) {
logInfo("[CONFERIR] '${nome}' é v${versaoArquivo} — não carrega cena; cenas NÃO foram conferidas.")
} else {
Map doArq = [:]
((bloco.scenes ?: []) as List).each { c ->
if (c?.id) doArq[(c.id as Integer).toString()] = ((c.name ?: "") as String).trim()
}
Map nomes = (state.sceneNames instanceof Map) ? (state.sceneNames as Map) : [:]
nomes.each { chave, nomeAntigo ->
String k = chave as String
if (!doArq.containsKey(k)) {
div++
achados << "CRÍTICO: a cena ${k} ('${nomeAntigo}') sumiu do projeto — o botão dispara e NADA acontece"
} else if ((doArq[k] as String) != (nomeAntigo as String)) {
div++
achados << "CRÍTICO: a cena ${k} era '${nomeAntigo}' e agora é '${doArq[k]}' — " +
"renomeada, ou projeto recriado? Revise as regras que usam este botão"
}
}
doArq.each { k, n ->
if (!nomes.containsKey(k as String)) {
div++; achados << "cena ${k} ('${n}') está no arquivo e não no hub — salve o device para importar"
}
}
}
atuais.findAll { kindDe(it) != "cover" && getChildDevice(coverDni(it)) != null }.each {
div++
achados << "CRÍTICO: ${it} tem filho de cortina (${coverDni(it)}) mas o arquivo não marca " +
"mais este endereço como cortina — remova o filho manualmente se a mudança " +
"for intencional; nada foi apagado automaticamente"
}
} catch (Exception e) {
logError("[CONFERIR] ${e.message}")
sendEvent(name: "divergencias", value: -1)
return
}
sendEvent(name: "divergencias", value: div)
if (!div) {
logInfo("[CONFERIR] Hub e '${nome}' batem: ${((state.addrs ?: []) as List).size()} módulo(s) e " +
"${((state.sceneNames ?: [:]) as Map).size()} cena(s). ATENÇÃO: compara com o ARQUIVO, " +
"não com a central — se o xConfig mudou e não foi reexportado, isto não enxerga.")
} else {
achados.each { logWarn("[CONFERIR] ${it}") }
}
}
void componentOn(cd) {
if (isSceneDni(cd.deviceNetworkId as String)) { dispararCena(cd); return }
if (cd.typeName == CHILD_DIMMER) {
Map p = dniParts(cd.deviceNetworkId)
if (!p) { logError("[CMD] DNI inesperado: ${cd.deviceNetworkId}"); return }
Integer mem = GW_LAST_LEVEL[gwChKey(p.hex as String, p.ch as int)]
Integer cur = (cd.currentValue("level") ?: 0) as Integer
componentSetLevel(cd, (mem ?: (cur > 0 ? cur : 100)) as int)
return
}
componentSwitch(cd, 1)
}
void componentOff(cd) {
if (isSceneDni(cd.deviceNetworkId as String)) {
def filho = getChildDevice(cd.deviceNetworkId as String)
if (filho) filho.parse([[name: "switch", value: "off", descriptionText: "cena é momentânea"]])
return
}
componentSwitch(cd, 0)
}
void componentRefresh(cd) {
if (isSceneDni(cd.deviceNetworkId as String)) {
logDebug("[CENA] refresh não se aplica — cena não tem estado para ler")
return
}
String coverHex = coverHexOf(cd.deviceNetworkId as String)
if (coverHex) { askStatusMuted(coverHex); return }
Map p = dniParts(cd.deviceNetworkId)
if (p) { askStatusMuted(p.hex as String) } else { logWarn("[CMD] DNI inesperado no refresh: ${cd.deviceNetworkId}") }
}
void componentSetLevel(cd, level, duration = null) {
Map p = dniParts(cd.deviceNetworkId)
if (!p) { logError("[CMD] DNI inesperado: ${cd.deviceNetworkId}"); return }
if (!tipoConfere(cd, p)) return
if (duration != null) { logDebug("[CMD] duration=${duration} ignorado — rampa é do módulo") }
int lvl = Math.max(0, Math.min(100, ((level ?: 0) as int)))
if (lvl > 0) { GW_LAST_LEVEL[gwChKey(p.hex as String, p.ch as int)] = lvl } 
GW_LAST_SENDMD_AT[gwKey(p.hex as String)] = now()
caSend("mdcmd_sendmd,${hexToDec(p.hex as String)},${p.ch},${levelToRaw(lvl)}")
}
void componentStartLevelChange(cd, direction) { logDebug("[CMD] startLevelChange(${direction}) sem suporte no protocolo xBus — no-op") }
void componentStopLevelChange(cd) { logDebug("[CMD] stopLevelChange — no-op") }
private Boolean coverPronta(String hex) {
Map ver = (state.coverVerified instanceof Map) ? (state.coverVerified as Map) : [:]
if (ver[hex] == true) return true
logError("[CORTINA] ${hex} está 'nao-verificado' — o módulo ainda não " +
"confirmou a configuração (ou divergiu do arquivo). Comando recusado.")
publicarFeedbackCortina() 
return false
}
private void criarCortinasDoArquivo() {
((state.addrs ?: []) as List).each { String hex ->
if (kindDe(hex as String) != "cover") return
if (getChildDevice(coverDni(hex as String))) return
String rotulo = rotuloCortina(hex as String) ?: (hex as String)
addChildDevice("hubitat", CHILD_SHADE, coverDni(hex as String),
[name: "${hex} cortina", isComponent: true, label: rotulo])
logInfo("[CHILD] Criado ${coverDni(hex as String)} (cortina, do arquivo) — " +
"${rotulo}. Fica 'nao-verificado' — comando é RECUSADO até o " +
"módulo responder ao getconfigmd.")
}
}
private void publicarFeedbackCortina() {
List cortinas = ((state.addrs ?: []) as List).findAll { kindDe(it as String) == "cover" }
if (!cortinas) return
Map ver = (state.coverVerified instanceof Map) ? (state.coverVerified as Map) : [:]
List pendentes = cortinas.findAll { ver[it as String] != true }
sendEvent(name: "feedback", value: pendentes ? "nao-verificado" : "otimista",
descriptionText: pendentes
? "cortina(s) não confirmada(s) pelo módulo: ${pendentes.join(', ')} — comando nelas é RECUSADO"
: "estado das cortinas é o comandado, sem leitura de posição real")
}
void componentOpen(cd) { cortinaCmd(cd, 2, 0, "open", 100) }
void componentClose(cd) { cortinaCmd(cd, 2, 2, "closed", 0) }
void componentStopPositionChange(cd) { cortinaCmd(cd, 2, 1, "partially open") }
void componentSetPosition(cd, pos) {
Integer p = Math.max(0, Math.min(100, (pos ?: 0) as Integer))
String estado = (p == 0) ? "closed" : (p == 100) ? "open" : "partially open"
cortinaCmd(cd, 0, levelToRaw(p), estado, p) 
}
void componentStartPositionChange(cd, direction) {
cortinaCmd(cd, 2, (direction == "close") ? 2 : 0, "partially open")
}
private void cortinaCmd(cd, int registrador, int valor, String windowShade, Integer posicao = null) {
String hex = coverHexOf(cd.deviceNetworkId as String)
if (!hex) { logError("[CMD] DNI de cortina inesperado: ${cd.deviceNetworkId}"); return }
if (!coverPronta(hex)) return
GW_LAST_SENDMD_AT[gwKey(hex)] = now()
if (!caSend("mdcmd_sendmd,${hexToDec(hex)},${registrador},${valor}")) return
def filho = getChildDevice(coverDni(hex)) 
if (filho && windowShade) {
List eventos = [[name: "windowShade", value: windowShade,
descriptionText: "${filho.displayName} ${windowShade} (otimista)"]]
if (posicao != null) {
eventos << [name: "position", value: posicao,
descriptionText: "${filho.displayName} position ${posicao} (otimista)"]
}
filho.parse(eventos)
}
publicarFeedbackCortina()
}
private void componentSwitch(cd, int val) {
Map p = dniParts(cd.deviceNetworkId)
if (!p) { logError("[CMD] DNI inesperado: ${cd.deviceNetworkId}"); return }
if (!tipoConfere(cd, p)) return
GW_LAST_SENDMD_AT[gwKey(p.hex as String)] = now()
caSend("mdcmd_sendmd,${hexToDec(p.hex as String)},${p.ch},${val}")
}
private boolean tipoConfere(cd, Map p) {
List tipos = (state.chTypes instanceof Map) ? (state.chTypes[p.hex] as List) : null
Integer tipo = (tipos && (p.ch as int) < tipos.size()) ? (tipos[p.ch as int] as Integer) : null
if (tipo == null) return true
String esperado = (tipo == CFG_ONOFF_OUT) ? CHILD_SWITCH :
(tipo == CFG_DIMMER_OUT) ? CHILD_DIMMER : null
if (esperado == null || cd.typeName == esperado) return true
logError("[CMD] RECUSADO: ${cd.deviceNetworkId} é '${cd.typeName}' mas o canal é do tipo ${tipo} " +
"('${esperado}'). O xConfig mudou o tipo — remova o filho e clique Initialize.")
return false
}
private Map dniParts(String dni) {
def m = (dni =~ /-([0-9A-F]{6})-([0-2])$/)
if (!m.find()) return null
String h = m.group(1)
return [hex: "${h[0..1]}-${h[2..3]}-${h[4..5]}".toString(), ch: (m.group(2) as Integer)]
}
