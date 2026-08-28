/**
 * ControlArt RF Cortinas
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
@Field static final List<Map> CHILD_BUTTONS = [
[idx: 1, label: "Subir", cmd: "up"],
[idx: 2, label: "Parar", cmd: "stop"],
[idx: 3, label: "Descer", cmd: "down"]
]
@Field static final List<String> IMPORT_KEYS = ["up", "stop", "down"]
@Field static final Map<String, String> IMPORT_SLOT = [
"up": "rfUp", "stop": "rfStop", "down": "rfDown"
]
@Field static final Map<String, String> PTBR_ALIAS = [
"subir": "up", "sobe": "up", "abrir": "up", "abre": "up",
"descer": "down", "desce": "down", "fechar": "down", "fecha": "down",
"parar": "stop", "para": "stop", "pausar": "stop"
]
metadata {
definition(
name: "ControlArt RF Cortinas",
namespace: "tecnosimples",
author: "TecnoSimples"
) {
capability "WindowShade" 
capability "Actuator"
capability "PushableButton"
capability "Initialize"
capability "Refresh"
command "up" 
command "stop"
command "down"
command "reconnect"
command "recreateButtons"
command "CodigoRF_up", [[name: "RFcode*", type: "STRING", description: "SUBIR/ABRIR — colar o código sendrf"]]
command "CodigoRF_stop", [[name: "RFcode*", type: "STRING", description: "PARAR — colar o código sendrf"]]
command "CodigoRF_down", [[name: "RFcode*", type: "STRING", description: "DESCER/FECHAR — colar o código sendrf"]]
command "verificarLicenca"
command "importarDoProjeto"
command "conferirProjeto"
attribute "boardstatus", "string"
attribute "status", "string"
attribute "feedback", "string"
attribute "licenca", "string"
attribute "hubUID", "string"
attribute "importStatus", "enum", ["ok", "aviso", "erro"]
attribute "importDetail", "string"
attribute "divergencias", "number"
}
preferences {
section("Conexão (gateway 7Port)") {
input name: "device_IP_address", type: "text", title: "IP do 7Port", required: true
input name: "device_port", type: "number", title: "Porta TCP", required: true, defaultValue: 4998
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
section("Aparelho") {
input name: "coverProfile", type: "enum", title: "Perfil (rótulo — não muda capabilities; RTS/Somfy usa códigos sendrf_rc)", options: ["Cortina", "Persiana", "RTS-Somfy"], defaultValue: "Cortina", required: false
}
section("Childs") {
input name: "createButtonChildren", type: "bool", title: "Criar child buttons Subir/Parar/Descer", defaultValue: true
}
section("Saúde da conexão") {
input name: "hbInterval", type: "number", title: "Heartbeat / keep-alive (s) — status offline proativo", defaultValue: 30, range: "5..300", required: false
input name: "tcpTransiente", type: "bool", defaultValue: false,
title: "Fechar a conexão entre comandos",
description: "LIGUE quando várias cortinas dividem o mesmo gateway (a partir de ~8). Cada device mantém uma sessão TCP aberta e o gateway tem teto — passando dele o comando some sem erro. Ligada, o driver conecta só para enviar. Em troca, boardstatus deixa de acompanhar o link entre comandos e o heartbeat acima fica inativo."
}
section("Logs") {
input name: "logEnable", type: "bool", title: "Ativar logs de debug (auto-off 30 min)", defaultValue: false
}
}
}
def installed() {
log.info "[CORTINA] Instalado: ${device.displayName}"
sendEvent(name: "numberOfButtons", value: 3)
sendEvent(name: "status", value: "stop")
coverBaseline()
}
def updated() { caScheduleLogsOff(); initialize() }
def initialize() {
unschedule()
sendEvent(name: "numberOfButtons", value: 3)
sendEvent(name: "feedback", value: "otimista")
coverBaseline()
if (settings.createButtonChildren != false) createButtonChildren()
caScheduleLogsOff()
caConnect()
}
private void coverBaseline() {
if (device.currentValue("windowShade") == null) sendEvent(name: "windowShade", value: "unknown")
}
def uninstalled() { caDisconnect() }
def reconnect() { caConnect() }
def refresh() { logInfo("[CORTINA] Gateway silencioso — estado da conexão via socketStatus/envios.") } 
void caAfterConnect() { caProbeGatewayPoll() } 
String caPollCommand() { return null }
def CodigoRF_up(String code) { state.rfUp = (code ?: "").trim(); logInfo("[CFG] Código SUBIR gravado (${state.rfUp.length()} chars)") }
def CodigoRF_stop(String code) { state.rfStop = (code ?: "").trim(); logInfo("[CFG] Código PARAR gravado (${state.rfStop.length()} chars)") }
def CodigoRF_down(String code) { state.rfDown = (code ?: "").trim(); logInfo("[CFG] Código DESCER gravado (${state.rfDown.length()} chars)") }
def up() {
if (!sendRF(state.rfUp, "Subir")) return
sendEvent(name: "status", value: "up")
coverEvent("open")
}
def stop() {
if (!sendRF(state.rfStop, "Parar")) return
sendEvent(name: "status", value: "stop")
coverEvent("stop")
}
def down() {
if (!sendRF(state.rfDown, "Descer")) return
sendEvent(name: "status", value: "down")
coverEvent("close")
}
def open() { up() }
def close() { down() }
def stopPositionChange() { stop() }
def startPositionChange(direction) {
switch ((direction ?: "") as String) {
case "open": up(); break
case "close": down(); break
default: logWarn("[CORTINA] startPositionChange com direção inválida: ${direction}")
}
}
def setPosition(pos) {
Integer n
try { n = pos as Integer } catch (Exception ignored) { logWarn("[CORTINA] setPosition inválido: ${pos}"); return }
if (n == 0) { close(); return }
if (n == 100) { open(); return }
logWarn("[CORTINA] setPosition ${n}% ignorado — RF 433 sem feedback: só 0 (fechar) e 100 (abrir) [A3].")
}
private void coverEvent(String cmd) {
switch (cmd) {
case "open":
sendEvent(name: "windowShade", value: "open")
sendEvent(name: "position", value: 100)
break
case "close":
sendEvent(name: "windowShade", value: "closed")
sendEvent(name: "position", value: 0)
break
case "stop":
sendEvent(name: "windowShade", value: "partially open") 
break
}
logDebug("[CORTINA] WindowShade otimista: ${cmd} (RF enviado, sem confirmação de posição)")
}
def push(pushed) {
if (pushed == null) { logWarn("[BTN] push nulo ignorado"); return }
Integer n
try { n = pushed as Integer } catch (Exception ignored) { logWarn("[BTN] push inválido: ${pushed}"); return }
sendEvent(name: "pushed", value: n, isStateChange: true)
switch (n) {
case 1: up(); break
case 2: stop(); break
case 3: down(); break
default: logWarn("[BTN] Botão ${n} inválido (1=Subir, 2=Parar, 3=Descer)")
}
}
private Boolean sendRF(String code, String label) {
if (!code?.trim()) {
logWarn("[RF] Código de '${label}' não configurado — use CodigoRF_up/stop/down com o sendrf completo do 7Config.")
return false
}
String frame = code.trim()
String low = frame.toLowerCase()
if (!(low.startsWith("sendrf") || low.startsWith("sendrf_rc"))) {
logWarn("[RF] Código de '${label}' deve começar com sendrf/sendrf_rc — cole o código completo do 7Config.")
return false
}
return caSend(frame)
}
void handleLine(String line) {
if (line.startsWith("completerf") || line.startsWith("completeir")) {
logDebug("[RX] RF confirmado pelo gateway")
return
}
logDebug("[RX] Linha não tratada: ${line}")
}
def recreateButtons() { createButtonChildren() }
private String btnDni(int idx) { return "${device.id}-BTN-${idx}" }
private void createButtonChildren() {
CHILD_BUTTONS.each { Map b ->
String dni = btnDni(b.idx as int)
def child = getChildDevice(dni)
if (!child) {
child = addChildDevice("hubitat", "Generic Component Switch", dni,
[name: "${b.label} ${device.displayName}", isComponent: true])
}
child.updateDataValue("cmd", b.cmd as String)
child.parse([[name: "switch", value: "off"]])
}
}
void componentRefresh(cd) {   }
void componentOff(cd) {   }
void componentOn(cd) {
String cmd = cd.getDataValue("cmd") ?: ""
switch (cmd) {
case "up": up(); break
case "stop": stop(); break
case "down": down(); break
default: logWarn("[BTN] Child ${cd.displayName} com cmd desconhecido: '${cmd}'")
}
runInMillis(800, "childBtnOff", [overwrite: false, data: [dni: cd.deviceNetworkId]])
}
def childBtnOff(Map data) {
def child = getChildDevice((data?.dni ?: "") as String)
if (child) child.parse([[name: "switch", value: "off"]])
}
def importarDoProjeto() {
caImportarDoProjeto(
IMPORT_KEYS, PTBR_ALIAS, null,
{ k -> state[IMPORT_SLOT[k]] },
{ k, v -> state[IMPORT_SLOT[k]] = v })
}
def conferirProjeto() {
caConferirProjetoIrrf(
IMPORT_KEYS, PTBR_ALIAS, null,
{ k -> state[IMPORT_SLOT[k]] })
}
