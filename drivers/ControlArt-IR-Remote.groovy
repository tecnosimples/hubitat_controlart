/**
 * ControlArt IR TV e Som
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
@Field static final Map<Integer, String> BTN = [
0: "poweroff", 1: "poweron", 2: "mute", 3: "source", 4: "back", 5: "menu",
6: "hdmi1", 7: "hdmi2", 8: "up", 9: "down", 10: "right", 11: "left",
12: "confirm", 13: "exit", 14: "home", 18: "chanup", 19: "chandown",
21: "volup", 22: "voldown",
23: "num0", 24: "num1", 25: "num2", 26: "num3", 27: "num4",
28: "num5", 29: "num6", 30: "num7", 31: "num8", 32: "num9",
33: "extra1", 34: "extra2", 35: "extra3",
38: "netflix", 39: "amazon", 40: "youtube", 41: "poweroff",
42: "extra4", 43: "extra5", 44: "extra6", 45: "extra7",
46: "btnA", 47: "btnB", 48: "btnC", 49: "btnD",
50: "play", 51: "pause", 52: "next", 53: "guide", 54: "info",
55: "tools", 56: "smarthub", 57: "prevchannel"
]
@Field static final Map<String, String> IRWEB_TV = [
"off": "poweroff", "on": "poweron", "power": "powertoggle",
"mute": "mute", "source": "source", "input": "source", "menu": "menu",
"hdmi1": "hdmi1", "hdmi2": "hdmi2",
"<": "left", ">": "right", "^": "up", "v": "down",
"left": "left", "right": "right", "up": "up", "down": "down",
"ok": "confirm", "enter": "confirm", "exit": "exit", "home": "home",
"return": "back", "back": "back",
"ch+": "chanup", "ch-": "chandown", "channel+": "chanup", "channel-": "chandown",
"v+": "volup", "v-": "voldown", "vol+": "volup", "vol-": "voldown",
"appamazon": "amazon", "appyoutube": "youtube", "appnetflix": "netflix",
"amazon": "amazon", "youtube": "youtube", "netflix": "netflix",
"<<-": "rewind", "->>": "fastforward", "play": "play", "pause": "pause", "stop": "stop",
"info": "info", "guide": "guide", "tools": "tools", "smarthub": "smarthub",
"pre-ch": "prevchannel", "chlist": "channellist",
"a": "btnA", "b": "btnB", "c": "btnC", "d": "btnD",
"btnextra1": "extra1", "btnextra2": "extra2", "btnextra3": "extra3", "btnextra4": "extra4",
"btnextra5": "extra5", "btnextra6": "extra6", "btnextra7": "extra7"
]
@Field static final List<String> ALL_KEYS = [
"poweron", "poweroff", "powertoggle", "mute", "source", "back", "menu",
"hdmi1", "hdmi2", "up", "down", "left", "right", "confirm", "exit", "home",
"chanup", "chandown", "volup", "voldown", "muteon", "muteoff",
"num0", "num1", "num2", "num3", "num4", "num5", "num6", "num7", "num8", "num9",
"extra1", "extra2", "extra3", "extra4", "extra5", "extra6", "extra7",
"btnA", "btnB", "btnC", "btnD",
"play", "pause", "stop", "next", "rewind", "fastforward",
"guide", "info", "tools", "smarthub", "prevchannel", "channellist",
"netflix", "amazon", "youtube"
]
@Field static final Map<String, String> PTBR_ALIAS = [
"ligar": "poweron", "liga": "poweron", "acender": "poweron",
"desligar": "poweroff", "desliga": "poweroff", "apagar": "poweroff",
"power": "powertoggle", "liga/desliga": "powertoggle",
"volume+": "volup", "volume-": "voldown", "aumentar": "volup", "diminuir": "voldown",
"canal+": "chanup", "canal-": "chandown",
"mudo": "mute", "semsom": "mute",
"fonte": "source", "entrada": "source",
"voltar": "back", "sair": "exit", "inicio": "home", "menu": "menu",
"confirmar": "confirm", "ok": "confirm",
"cima": "up", "baixo": "down", "esquerda": "left", "direita": "right",
"tocar": "play", "pausar": "pause", "parar": "stop",
"avancar": "fastforward", "retroceder": "rewind", "guia": "guide"
]
@Field static final Integer MAX_TOUCHES = 25
metadata {
definition(
name: "ControlArt IR TV e Som", 
namespace: "tecnosimples",
author: "TecnoSimples"
) {
capability "Switch" 
capability "TV" 
capability "SamsungTV" 
capability "PushableButton"
capability "AudioVolume" 
capability "Actuator"
capability "Configuration"
capability "Initialize"
capability "Refresh"
command "CodigoHEX", [
[name: "Tecla*", type: "ENUM", constraints: ALL_KEYS, description: "Tecla que receberá o código"],
[name: "HEXcode*", type: "STRING", description: "Código sendir completo ou só o payload"]
]
command "GetRemoteDATA"
command "listCodes"
command "clearCodes"
command "sendKey", [[name: "Tecla*", type: "ENUM", constraints: ALL_KEYS]]
command "setMuteState", [[name: "Estado*", type: "ENUM", constraints: ["muted", "unmuted"], description: "Re-sincroniza o mute otimista (sem emitir IR) após uso do controle físico"]]
command "reconnect"
command "arrowUp"; command "arrowDown"; command "arrowLeft"; command "arrowRight"
command "enter"; command "confirm"; command "exit"; command "Return"
command "back"; command "home"; command "menu"; command "guide"
command "info"; command "tools"; command "smarthub"; command "previousChannel"
command "channelList"
command "source"; command "sourceToggle"; command "hdmi1"; command "hdmi2"
command "poweron"; command "poweroff"
command "play"; command "pause"; command "stop"
command "next"; command "rewind"; command "fastForward"
command "appNetflix"; command "appYouTube"; command "appAmazonPrime"
command "num0"; command "num1"; command "num2"; command "num3"; command "num4"
command "num5"; command "num6"; command "num7"; command "num8"; command "num9"
command "btnA"; command "btnB"; command "btnC"; command "btnD"
command "appOpenByName", [[name: "App*", type: "STRING", description: "netflix | youtube | amazon"]]
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
section("Conexão (gateway 7Port ou Xport)") {
input name: "device_IP_address", type: "text", title: "IP do gateway", required: true
input name: "device_port", type: "number", title: "Porta TCP", required: true, defaultValue: 4998
input name: "irChannel", type: "number", title: "Canal IR de saída (1-8) — usado quando o código não vem com 'sendir,'", defaultValue: 1, range: "1..8"
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
input name: "volumeStep", type: "number", title: "Passo de volume por toque (%)", defaultValue: 2, range: "1..10", required: false
}
section("Importação ir.molsmart.com.br (opcional)") {
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
log.info "[IR] Instalado: ${device.displayName}"
sendEvent(name: "numberOfButtons", value: 57)
caVolBaseline()
}
def updated() { caScheduleLogsOff(); initialize() }
def configure() { initialize() }
def initialize() {
unschedule()
caVolCancel(null) 
sendEvent(name: "numberOfButtons", value: 57)
if (!(state.codes instanceof Map)) state.codes = [:]
caVolBaseline() 
caScheduleLogsOff()
caConnect()
}
def uninstalled() { caDisconnect() }
def reconnect() { caConnect() }
def refresh() { logInfo("[IR] Gateway silencioso — estado da conexão via socketStatus/envios.") }
void caAfterConnect() { caProbeGatewayPoll() } 
String caPollCommand() { return null }
def CodigoHEX(String tecla, String hexcode) {
if (!(state.codes instanceof Map)) state.codes = [:]
state.codes[tecla] = (hexcode ?: "").trim() 
logInfo("[CFG] Código da tecla '${tecla}' gravado (${state.codes[tecla].length()} chars)")
}
def clearCodes() {
state.codes = [:]
logInfo("[CFG] Todos os códigos IR apagados")
}
def listCodes() {
Map codes = (state.codes instanceof Map) ? state.codes : [:]
String resumo = ALL_KEYS.collect { k -> "${k}: ${codes[k] ? 'OK' : '—'}" }.join(" | ")
log.info "${device.displayName}: [CFG] ${resumo}"
}
private String caMapName(String rawName) {
String n = caNormKey(rawName)
if (n ==~ /\d/) return "num${n}"
return IRWEB_TV[n]
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
sendEvent(name: "Controle", value: data?.name ?: "importado")
sendEvent(name: "TipoControle", value: data?.type ?: "")
logInfo("[CFG] Controle '${data?.name}' (tipo '${data?.type}') importado POR NOME: ${gravados} teclas de ${fonte.size()} do controle")
if (desconhecidas) logWarn("[CFG] Teclas com código que este driver não conhece (não gravadas): ${desconhecidas.join(', ')} — me reporte para entrarem no mapa.")
}
} catch (Exception e) {
logError("[CFG] Falha ao importar controle: ${e.message}")
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
def on() {
if (sendKey("poweron")) {
sendEvent(name: "switch", value: "on")
sendEvent(name: "power", value: "on") 
return
}
if ((state.codes instanceof Map) && state.codes["powertoggle"] && !state.codes["poweron"]) {
logWarn("[IR] on() sem código poweron absoluto — powertoggle NÃO é usado (não-idempotente). Configure poweron via CodigoHEX.")
}
}
def off() {
if (sendKey("poweroff")) {
sendEvent(name: "switch", value: "off")
sendEvent(name: "power", value: "off") 
return
}
if ((state.codes instanceof Map) && state.codes["powertoggle"] && !state.codes["poweroff"]) {
logWarn("[IR] off() sem código poweroff absoluto — powertoggle NÃO é usado (não-idempotente). Configure poweroff via CodigoHEX.")
}
}
def push(pushed) {
if (pushed == null) { logWarn("[BTN] push nulo ignorado"); return }
Integer n
try { n = pushed as Integer } catch (Exception ignored) { logWarn("[BTN] push inválido: ${pushed}"); return }
sendEvent(name: "pushed", value: n, isStateChange: true)
String key = BTN[n]
if (!key) { logWarn("[BTN] Botão ${n} sem tecla mapeada (ver tabela no cabeçalho)"); return }
sendKey(key)
}
def arrowUp() { sendKey("up") }
def arrowDown() { sendKey("down") }
def arrowLeft() { sendKey("left") }
def arrowRight() { sendKey("right") }
def enter() { sendKey("confirm") }
def confirm() { sendKey("confirm") }
def exit() { sendKey("exit") }
def Return() { sendKey("back") } 
def back() { sendKey("back") } 
def home() { sendKey("home") }
def menu() { sendKey("menu") }
def guide() { sendKey("guide") }
def info() { sendKey("info") }
def tools() { sendKey("tools") }
def smarthub() { sendKey("smarthub") }
def source() { sendKey("source") }
def sourceToggle() { sendKey("source") }
def hdmi1() { sendKey("hdmi1") }
def hdmi2() { sendKey("hdmi2") }
def channelUp() { sendKey("chanup") }
def channelDown() { sendKey("chandown") }
def previousChannel() { sendKey("prevchannel") }
def channelList() { sendKey("channellist") }
def play() { sendKey("play") }
def pause() { sendKey("pause") }
def stop() { sendKey("stop") }
def next() { sendKey("next") }
def rewind() { sendKey("rewind") }
def fastForward() { sendKey("fastforward") }
def poweron() { on() }
def poweroff() { off() }
def appNetflix() { sendKey("netflix") }
def appYouTube() { sendKey("youtube") }
def appAmazonPrime() { sendKey("amazon") }
def num0() { sendKey("num0") }; def num1() { sendKey("num1") }; def num2() { sendKey("num2") }
def num3() { sendKey("num3") }; def num4() { sendKey("num4") }; def num5() { sendKey("num5") }
def num6() { sendKey("num6") }; def num7() { sendKey("num7") }; def num8() { sendKey("num8") }
def num9() { sendKey("num9") }
def btnA() { sendKey("btnA") }; def btnB() { sendKey("btnB") }
def btnC() { sendKey("btnC") }; def btnD() { sendKey("btnD") }
def appOpenByName(String appName) {
String key = (appName ?: "").toLowerCase().trim()
if (key == "prime video" || key == "prime") key = "amazon"
if (!(key in ["netflix", "amazon", "youtube"])) {
logWarn("[IR] App '${appName}' sem tecla no controle (só netflix/amazon/youtube)")
return
}
sendKey(key)
}
def setPictureMode(mode) { logWarn("[TV] setPictureMode não existe por IR — ignorado (${mode})") }
def setSoundMode(mode) { logWarn("[TV] setSoundMode não existe por IR — ignorado (${mode})") }
def showMessage(a, b, c, d) { logWarn("[TV] showMessage não existe por IR — ignorado") }
private Boolean caTxKey(String tecla) {
String code = (state.codes instanceof Map) ? state.codes[tecla] : null
if (!code) {
logWarn("[IR] Tecla '${tecla}' sem código — configure via CodigoHEX ou GetRemoteDATA.")
return false
}
String frame = code.startsWith("sendir")
? code
: "sendir,1:${(settings.irChannel ?: 1) as int},1,${code}"
state.waitingAck = true
Boolean ok = caSend(frame)
if (ok) runIn(6, "ackTimeout")
else state.waitingAck = false
return ok
}
Boolean sendKey(String tecla) {
caVolCancel(null)
Boolean ok = caTxKey(tecla)
sendEvent(name: "lastCommandStatus", value: ok ? "enviado: ${tecla}" : "falha de envio: ${tecla}")
return ok
}
def ackTimeout() {
if (((state.volPending ?: 0) as int) > 0) { 
logWarn("[VOL] Fila de volume sem confirmação (6s) — cancelando o restante.")
caVolCancel(null)
}
if (state.waitingAck) {
state.waitingAck = false
sendEvent(name: "lastCommandStatus", value: "sem confirmação (completeir não chegou)")
logWarn("[IR] Gateway não confirmou o último sendir — verifique canal/emissor.")
}
}
private int caVolStep() { return Math.max(1, Math.min(10, (settings?.volumeStep ?: 2) as int)) }
private void caVolBaseline() {
if (device.currentValue("volume") == null) sendEvent(name: "volume", value: 50)
if (device.currentValue("mute") == null) sendEvent(name: "mute", value: "unmuted")
}
def volumeUp() { caVolManualStep("volup") }
def volumeDown() { caVolManualStep("voldown") }
private void caVolManualStep(String key) {
caVolCancel(null)
if (caTxKey(key)) {
int cur = (device.currentValue("volume") ?: 50) as int
int step = caVolStep()
sendEvent(name: "volume", value: (key == "volup") ? Math.min(100, cur + step) : Math.max(0, cur - step))
sendEvent(name: "lastCommandStatus", value: "enviado: ${key}")
}
}
def setVolume(volumelevel) {
Integer target
try { target = Math.max(0, Math.min(100, volumelevel as int)) }
catch (Exception ignored) { logWarn("[VOL] setVolume inválido: ${volumelevel}"); return }
int cur = (device.currentValue("volume") ?: 50) as int
int delta = target - cur
if (delta == 0) return
int step = caVolStep()
int want = (int) Math.ceil(Math.abs(delta) / (double) step) 
int touches = Math.min(MAX_TOUCHES, Math.max(1, want))
caVolCancel(null)
state.volKey = delta > 0 ? "volup" : "voldown"
state.volPending = touches
if (touches < want) logWarn("[VOL] setVolume(${target}) limitado a ${touches} toques (MAX_TOUCHES=${MAX_TOUCHES}) — repita se precisar de mais.")
caVolDispatch()
}
private void caVolDispatch() {
if (((state.volPending ?: 0) as int) <= 0) { caVolCancel(null); return }
state.volGen = ((state.volGen ?: 0) as int) + 1
state.volInFlight = true
int gen = state.volGen as int
if (!caTxKey(state.volKey)) { logWarn("[VOL] Falha ao enviar toque — cancelando fila."); caVolCancel(null); return }
runInMillis(800, "caVolTick", [data: [gen: gen]])
}
def caVolTick(Map data) {
if (!state.volInFlight) return
if ((data?.gen as Integer) != (state.volGen as Integer)) return
caVolAdvance(true)
}
private void caVolAdvance(boolean viaTimer) {
if (!state.volInFlight) return
state.volInFlight = false
unschedule("caVolTick")
int cur = (device.currentValue("volume") ?: 50) as int
int step = caVolStep()
sendEvent(name: "volume", value: (state.volKey == "volup") ? Math.min(100, cur + step) : Math.max(0, cur - step))
state.volPending = ((state.volPending ?: 0) as int) - 1
if (viaTimer) state.volStaleAcks = ((state.volStaleAcks ?: 0) as int) + 1
if (((state.volPending ?: 0) as int) > 0) {
caVolDispatch()
} else {
state.volPending = 0
unschedule("ackTimeout") 
}
}
private void caVolCancel(String reason) {
if (((state.volPending ?: 0) as int) > 0 && reason) logWarn("[VOL] Fila cancelada: ${reason}")
state.volPending = 0
state.volInFlight = false
state.volStaleAcks = 0
unschedule("caVolTick")
}
def mute() { caSetMute("muted") }
def unmute() { caSetMute("unmuted") }
private void caSetMute(String target) {
caVolCancel(null)
String absKey = (target == "muted") ? "muteon" : "muteoff"
if ((state.codes instanceof Map) && state.codes[absKey]) {
if (caTxKey(absKey)) sendEvent(name: "mute", value: target)
return
}
if ((state.codes instanceof Map) && state.codes["mute"]) {
if (caTxKey("mute")) {
sendEvent(name: "mute", value: target)
logWarn("[VOL] 'mute' é toggle — otimista pode dessincronizar do controle físico; use setMuteState p/ corrigir.")
}
} else {
logWarn("[VOL] Sem código de mute (mute/muteon/muteoff) configurado.")
}
}
def setMuteState(String estado) {
if (estado in ["muted", "unmuted"]) { sendEvent(name: "mute", value: estado); logInfo("[VOL] mute re-sincronizado: ${estado}") }
else logWarn("[VOL] setMuteState inválido: ${estado}")
}
void handleLine(String line) {
if (line.startsWith("completeir")) {
state.waitingAck = false
unschedule("ackTimeout")
if (((state.volStaleAcks ?: 0) as int) > 0) {
state.volStaleAcks = ((state.volStaleAcks as int) - 1)
return
}
if (state.volInFlight) {
caVolAdvance(false)
return
}
sendEvent(name: "lastCommandStatus", value: "confirmado")
logDebug("[RX] IR confirmado pelo gateway")
return
}
if (line.toLowerCase().contains("command error")) { 
state.waitingAck = false
unschedule("ackTimeout")
caVolCancel("gateway rejeitou o comando (Command ERROR)")
sendEvent(name: "lastCommandStatus", value: "rejeitado pelo gateway (comando malformado)")
logWarn("[IR] Gateway rejeitou o comando (Command ERROR) — código malformado; rode GetRemoteDATA de novo (v1.2.3 repara códigos truncados).")
return
}
if (line.contains("Blaster not found")) { 
state.waitingAck = false
unschedule("ackTimeout")
caVolCancel("canal IR inexistente (Blaster not found)")
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
