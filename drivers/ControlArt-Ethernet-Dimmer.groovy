/**
 * ControlArt Ethernet Dimmer
 *
 * TecnoSimples Tecnologia LTDA
 * contato@tecnosimples.com.br | (14) 99760-6885
 * (c) 2026 TecnoSimples - Todos os direitos reservados.
 *
 * Produto licenciado. Distribuido via Hubitat Package Manager.
 * Uso restrito ao hub licenciado. Ver LICENSE no repositorio.
 * Versao do pacote: 1.0.4 | library embutida: 1.17.0
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
@Field static final String OUT_PREFIX = "DIM"
@Field static final Integer MAX_INPUTS = 12
@Field static final Integer MAX_CHANNELS = 9
metadata {
definition(
name: "ControlArt Ethernet Dimmer",
namespace: "tecnosimples",
author: "TecnoSimples"
) {
capability "Configuration"
capability "Initialize"
capability "Refresh"
capability "Switch" 
capability "PushableButton" 
capability "Actuator"
command "allOn"
command "allOff"
command "masterOn" 
command "masterOff"
command "setAllLevel", [
[name: "Nível*", type: "NUMBER", description: "0-100 (%)"],
[name: "Rampa (s)", type: "NUMBER", description: "0-25.5 s, opcional"]
]
command "getmac"
command "getfw"
command "getstatus"
command "getAnalog" 
command "reconnect"
command "verificarLicenca"
attribute "boardstatus", "string"
attribute "firmware", "string"
attribute "macaddr", "string"
attribute "temperature", "number"
attribute "voltage1", "number"
attribute "voltage2", "number"
attribute "licenca", "string"
attribute "hubUID", "string"
}
preferences {
section("Conexão") {
input name: "device_IP_address", type: "text", title: "IP da placa", required: true
input name: "device_port", type: "number", title: "Porta TCP", required: true, defaultValue: 4998
}
section("Canais") {
input name: "channels", type: "number", title: "Canais de dimmer em uso (1-9)", defaultValue: 9, range: "1..9"
input name: "dimmerRampSec", type: "decimal", title: "Rampa de dimerização (s, 0-25.5; 0 = instantâneo)", defaultValue: 0
}
section("Entradas") {
input name: "pulseDebounceMs", type: "number", title: "Debounce dos botões (ms)", defaultValue: 50
}
section("Telemetria") {
input name: "analogPollMinutes", type: "number", title: "Poll de temperatura/tensões (min, 0 = desligado)", defaultValue: 0
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
log.info "[DIMMER] Instalado: ${device.displayName}"
}
def updated() {
caScheduleLogsOff()
initialize()
}
def configure() { initialize() }
def initialize() {
unschedule()
state.chCount = Math.min(((settings.channels ?: MAX_CHANNELS) as int), MAX_CHANNELS)
if (!(state.prevInputs instanceof List) || state.prevInputs.size() != MAX_INPUTS) {
state.prevInputs = (0..<MAX_INPUTS).collect { 0 }
state.lastPulseTs = (0..<MAX_INPUTS).collect { 0L }
}
sendEvent(name: "numberOfButtons", value: MAX_INPUTS)
createChildren()
caScheduleLogsOff()
caConnect()
int pollMin = ((settings.analogPollMinutes ?: 0) as int)
if (pollMin > 0) runIn(pollMin * 60, "analogPoll")
}
def uninstalled() { caDisconnect() }
def reconnect() { caConnect() }
def refresh() { getstatus() }
void caAfterConnect() { runInMillis(200, "getmac") }
String caPollCommand() { return "get_firmware_version" }
def getmac() { caSend("get_mac_addr") }
def getfw() { caSend("get_firmware_version") }
def getstatus() {
String mac = state.macFmt
if (!mac) { logDebug("[CMD] getstatus sem MAC — pedindo get_mac_addr"); getmac(); return }
caSend("mdcmd_getmd,${mac}")
}
def on() { allOn() }
def off() { allOff() }
def allOn() { caMacCmd("mdcmd_setallonmd") } 
def allOff() { caMacCmd("mdcmd_setalloffmd") }
def masterOn() { caSend("mdcmd_setmasteronmd") } 
def masterOff() { caSend("mdcmd_setmasteroffmd") }
def setAllLevel(nivel, rampaSec = null) {
String mac = state.macFmt
if (!mac) { logWarn("[CMD] setAllLevel sem MAC — rodando getmac."); getmac(); return }
int lvl = Math.max(0, Math.min(100, (nivel as BigDecimal).intValue()))
int mask = (1 << ((state.chCount ?: MAX_CHANNELS) as int)) - 1
String cmd = "mdcmd_msenddimmer,${mac},${mask},${(int) Math.round(lvl * 2.55d)}"
String rmp = rampField(rampaSec)
caSend(rmp ? "${cmd},${rmp}" : cmd)
}
private void caMacCmd(String cmd) {
String mac = state.macFmt
if (!mac) { logWarn("[CMD] ${cmd} ignorado — MAC ainda não obtido."); getmac(); return }
caSend("${cmd},${mac}")
}
private String caLevelCmd(int channel0, int value255, rampSec = null) {
String mac = state.macFmt
if (!mac) return null
String cmd = "mdcmd_senddimmer,${mac},${channel0},${value255}"
def sec = (rampSec != null) ? rampSec : settings.dimmerRampSec
String rmp = rampField(sec)
return rmp ? "${cmd},${rmp}" : cmd
}
private String rampField(sec) {
BigDecimal s
try { s = (sec ?: 0) as BigDecimal } catch (Exception ignored) { return null }
int rmp = Math.max(0, Math.min(255, (int) Math.round(s * 10)))
return rmp > 0 ? "${rmp}" : null
}
def getAnalog() {
caSend("get_analog,0")
runInMillis(300, "getAnalog1")
runInMillis(600, "getAnalog2")
}
def getAnalog1() { caSend("get_analog,1") }
def getAnalog2() { caSend("get_analog,2") }
def analogPoll() {
getAnalog()
int pollMin = ((settings.analogPollMinutes ?: 0) as int)
if (pollMin > 0) runIn(pollMin * 60, "analogPoll")
}
void handleLine(String line) {
if (line.startsWith("macaddr")) {
List<String> pares = (line =~ /[0-9A-Fa-f]{2}/).collect { it.toString().toUpperCase() }
if (pares.size() >= 3) {
state.macFmt = pares[-3..-1].collect { "0x${it}" }.join(",")
sendEvent(name: "macaddr", value: state.macFmt)
logInfo("[RX] MAC da placa: ${state.macFmt}")
runInMillis(150, "getstatus")
} else {
logWarn("[RX] Não extraí MAC de: '${line}'")
}
return
}
if (line.startsWith("setdmmd,") || line.startsWith("setcmd,")) {
String[] f = line.split(",")
if (f.size() >= 2 + MAX_INPUTS + 1) updateIO(f)
else logDebug("[RX] frame curto (${f.size()} campos) — ignorado")
return
}
if (line.equalsIgnoreCase("MasterOn") || line.equalsIgnoreCase("MasterOff")) {
runInMillis(150, "getstatus") 
return
}
if (line.startsWith("Temperatura:")) {
sendEvent(name: "temperature", value: numTail(line), unit: "°C"); return
}
if (line.startsWith("Entrada1:")) {
sendEvent(name: "voltage1", value: numTail(line) / 10, unit: "V"); return
}
if (line.startsWith("Entrada2:")) {
sendEvent(name: "voltage2", value: numTail(line) / 10, unit: "V"); return
}
if (line ==~ /\d{3,6}/) {
sendEvent(name: "firmware", value: String.format("%.3f", (line as Integer) / 1000.0d))
return
}
logDebug("[RX] Linha não tratada: ${line}")
}
private BigDecimal numTail(String line) {
try { return (line.split(":")[1].trim() as BigDecimal) } catch (Exception ignored) { return 0 }
}
private void updateIO(String[] f) {
int inStart = 2
int outStart = inStart + MAX_INPUTS
long nowTs = now()
long debounce = ((settings.pulseDebounceMs ?: 50) as long)
for (int i = 0; i < MAX_INPUTS; i++) {
if (inStart + i >= f.size()) break
int cur = (f[inStart + i]?.trim() == "1") ? 1 : 0
int prev = ((state.prevInputs[i] ?: 0) as int)
if (cur == 1 && prev == 0) {
long lastTs = ((state.lastPulseTs[i] ?: 0L) as long)
if (nowTs - lastTs >= debounce) {
sendEvent(name: "pushed", value: i + 1, isStateChange: true, type: "digital",
descriptionText: "${device.displayName} entrada ${i + 1} acionada")
state.lastPulseTs[i] = nowTs
}
}
state.prevInputs[i] = cur
}
int chans = ((state.chCount ?: MAX_CHANNELS) as int)
boolean anyOn = false
for (int j = 0; j < chans; j++) {
int idx = outStart + j
if (idx >= f.size()) break
Integer v = parseFieldInt(f[idx])
if (v == null) continue
def cd = getChildDevice(dimDni(j + 1))
if (!cd) continue
String sw = (v > 0) ? "on" : "off"
if (v > 0) anyOn = true
List<Map> evts = []
if (cd.currentValue("switch") != sw) {
evts << [name: "switch", value: sw, descriptionText: "${cd.displayName} ${sw}"]
}
if (v > 0) {
Integer lvl = Math.max(1, Math.min(100, (int) Math.round(v / 2.55d)))
if (cd.currentValue("level") != lvl) {
evts << [name: "level", value: lvl, unit: "%", descriptionText: "${cd.displayName} nível ${lvl}%"]
}
}
if (evts) cd.parse(evts)
}
updateParentSwitch(anyOn)
}
private Integer parseFieldInt(String s) {
try { return (s?.trim() as Integer) } catch (Exception ignored) { return null }
}
private String dimDni(int n) { return "${device.id}-${OUT_PREFIX}-${n}" }
private void createChildren() {
int chans = ((state.chCount ?: MAX_CHANNELS) as int)
for (int i = 1; i <= chans; i++) {
if (!getChildDevice(dimDni(i))) {
addChildDevice("hubitat", "Generic Component Dimmer", dimDni(i),
[name: "${device.displayName} Canal ${i}", isComponent: true])
}
}
for (int k = chans + 1; k <= MAX_CHANNELS; k++) {
if (getChildDevice(dimDni(k))) deleteChildDevice(dimDni(k))
}
}
private void updateParentSwitch(boolean anyOn) {
String sw = anyOn ? "on" : "off"
if (device.currentValue("switch") != sw) {
sendEvent(name: "switch", value: sw, descriptionText: "${device.displayName} ${sw}")
}
}
void componentRefresh(cd) { getstatus() }
void componentOn(cd) {
Integer lvl = (cd.currentValue("level") ?: 100) as Integer
componentSetLevel(cd, lvl > 0 ? lvl : 100)
}
void componentOff(cd) { sendLevel(cd, 0, null) }
void componentSetLevel(cd, level, ramp = null) {
Integer lvl = Math.max(0, Math.min(100, (level as BigDecimal).intValue()))
sendLevel(cd, lvl, ramp)
}
void componentStartLevelChange(cd, direction) {
componentSetLevel(cd, direction == "up" ? 100 : 10) 
}
void componentStopLevelChange(cd) {   }
private void sendLevel(cd, int levelPct, rampSec) {
Integer ch = channelFromDni(cd.deviceNetworkId)
if (ch == null) { logError("[CMD] DNI inesperado: ${cd.deviceNetworkId}"); return }
String cmd = caLevelCmd(ch - 1, (int) Math.round(levelPct * 2.55d), rampSec)
if (!cmd) { logWarn("[CMD] Sem MAC — comando descartado; rodando getmac."); getmac(); return }
caSend(cmd) 
}
private Integer channelFromDni(String dni) {
def m = (dni =~ /-${OUT_PREFIX}-(\d+)$/)
return m.find() ? (m.group(1) as Integer) : null
}
