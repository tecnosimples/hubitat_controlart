/**
 * ControlArt Ethernet Relay
 *
 * TecnoSimples Tecnologia LTDA
 * contato@tecnosimples.com.br | (14) 99760-6885
 * (c) 2026 TecnoSimples - Todos os direitos reservados.
 *
 * Produto licenciado. Distribuido via Hubitat Package Manager.
 * Uso restrito ao hub licenciado. Ver LICENSE no repositorio.
 * Versao do pacote: 1.0.1 | library embutida: 1.15.0
 */
 







































































































































































































































































































import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap



 
@Field static ConcurrentHashMap<String, String> CA_RX_BUF = new ConcurrentHashMap<String, String>()
@Field static ConcurrentHashMap<String, Object> CA_RX_LOCK = new ConcurrentHashMap<String, Object>()
@Field static final Integer CA_HB_DEFAULT_SEC = 30 







@Field static final Integer CA_RECONNECT_MAX = 60
@Field static final Integer CA_RX_BUF_MAX = 8192 
 
@Field static final String CA_WM = "HPM"
 
@Field static final Boolean CA_WM_POR_HUB = true
 
@Field static final String CA_LIB_VER = "1.15.0"
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
runIn(caHbSec(), "caHeartbeat")
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



 
private int caHbSec() { return Math.max(5, Math.min(300, (settings?.hbInterval ?: CA_HB_DEFAULT_SEC) as int)) }
 
def caHeartbeat() {
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




if (state.caLinkDown == true) {
logWarn("[TX] '${cmd}' NÃO enviado — sem link com a central (nada foi ao fio).")


if (state.caIntentionalClose != true) runIn(1, "caReconnectKick")
return false
}
try {
logDebug("[TX] ${cmd}")
interfaces.rawSocket.sendMessage(cmd + "\r\n")
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
schedule("${j % 60} ${7 + (j % 5)} 3 * * ?", "caLicDaily")
}
def caLicDaily() {
long last = (caLicCache()?.lastCheck ?: 0L) as long
if ((now() - last) >= 4 * 86400_000L) caLicCheckNow()
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
@Field static final String OUT_PREFIX = "OUT"
@Field static final String IN_PREFIX = "IN"
@Field static final Integer MAX_OUTPUTS = 10
@Field static final Integer MAX_INPUTS = 12
@Field static final String CHILD_SHADE = "Generic Component Window Shade"
@Field static final String CHILD_SWITCH = "Generic Component Switch"
metadata {
definition(
name: "ControlArt Ethernet Relay",
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
command "toggleAll" 
command "masterOn"
command "masterOff"
command "getmac"
command "getfw"
command "getstatus"
command "reconnect"
command "verificarLicenca"
command "importarDoProjeto"
command "conferirProjeto"
command "limparOrfaos"
attribute "boardstatus", "string"
attribute "firmware", "string"
attribute "macaddr", "string"
attribute "licenca", "string"
attribute "hubUID", "string"






attribute "importStatus", "enum", ["aviso", "ok", "erro"]
attribute "importDetail", "string"




attribute "feedback", "string"
}
preferences {
section("Conexão") {
input name: "device_IP_address", type: "text", title: "IP da placa", required: true
input name: "device_port", type: "number", title: "Porta TCP", required: true, defaultValue: 4998
}
section("Import do projeto") {
input name: "moduleFile", type: "text", required: false,
title: "Arquivo de import (File Manager)",
description: "ex.: controlart-xbus.json — deixe vazio para manter a configuração manual (seção Canais)"
input name: "tempoCursoS", type: "number",
title: "Curso máximo da cortina (s) — teto de movimento travado",
defaultValue: 60, range: "5..600"
}
section("Canais") {
input name: "outputs", type: "number", title: "Relés em uso (1-10)", defaultValue: 10, range: "1..10"
input name: "inputs", type: "number", title: "Entradas em uso (1-12)", defaultValue: 12, range: "1..12"
input name: "inputsCreateContactChildren", type: "bool", title: "Criar childs Contact para as entradas", defaultValue: false
}
section("Entradas (pulso)") {
input name: "inputsPulseMode", type: "bool", title: "Entradas como pulso (momentâneo)", defaultValue: true
input name: "pulseDebounceMs", type: "number", title: "Debounce do pulso (ms)", defaultValue: 50
input name: "pulseReleaseMs", type: "number", title: "Retorno do contact para 'open' (ms)", defaultValue: 150
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
log.info "[RELAY] Instalado: ${device.displayName}"
}
def updated() {
caScheduleLogsOff()
initialize()
}
def configure() { initialize() }
def initialize() {
unschedule()
state.outCount = Math.min(((settings.outputs ?: MAX_OUTPUTS) as int), MAX_OUTPUTS)
state.inCount = Math.min(((settings.inputs ?: MAX_INPUTS) as int), MAX_INPUTS)
if (!(state.prevInputs instanceof List) || state.prevInputs.size() != MAX_INPUTS) {
state.prevInputs = (0..<MAX_INPUTS).collect { 0 }
state.lastPulseTs = (0..<MAX_INPUTS).collect { 0L }
}
sendEvent(name: "numberOfButtons", value: state.inCount)
createChildren()
caScheduleLogsOff()
caConnect()
}
def uninstalled() {
caDisconnect()
}
def reconnect() { caConnect() }
def refresh() { getstatus() }

void caAfterConnect() {
runInMillis(200, "getmac") 
}
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
private Boolean temCortina() { return !(((state.covers ?: []) as List).isEmpty()) }
 
private List canaisDeLuz(Set motoresPreCalculado = null) {
Set motores = motoresPreCalculado ?: motorOuts()
int n = (state.outCount ?: MAX_OUTPUTS) as int
List complemento = (0..<n).findAll { !motores.contains(it) }
if (!temCortina()) return complemento
Set relaysCh = ((state.relays ?: []) as List).collect { (it.ch as Integer) } as Set
return complemento.findAll { relaysCh.contains(it) }
}
 
private void massa(String cmdTodos, int valor) {
String mac = state.macFmt
if (!mac) { logWarn("[CMD] ${cmdTodos} ignorado — MAC ainda não obtido (rode getmac)."); getmac(); return }
if (valor == 0) { caSend("${cmdTodos},${mac}"); return }
if (!temCortina()) { caSend("${cmdTodos},${mac}"); return }
List luz = canaisDeLuz()
if (!luz) {
logWarn("[CMD] ${cmdTodos}: caixa sem saída de iluminação — o comando não " +
"se aplica a cortina. Nada foi enviado ao fio.")
return
}






luz.each { int ch -> caSend("mdcmd_sendrele,${mac},${ch},${valor}") }
logInfo("[CMD] ${cmdTodos} degradado: ${luz.size()} saída(s) de iluminação; " +
"par(es) de motor preservado(s).")
}
def allOn() { massa("mdcmd_setallonmd", 1) }
def allOff() { massa("mdcmd_setalloffmd", 0) } 
 
def masterOn() { masterBroadcast("mdcmd_setmasteronmd") }
def masterOff() { masterBroadcast("mdcmd_setmasteroffmd") }
private void masterBroadcast(String cmd) {
if (temCortina() || state.redeTemCortina) {
logError("[CMD] ${cmd} RECUSADO: é broadcast SEM MAC — atinge toda a rede " +
"ControlART, e a REDE do último import tem cortina (nesta caixa ou " +
"noutra do mesmo arquivo), inclusive caixas cujo modo este device " +
"não pode consultar; um par de motor energizado nos dois sentidos " +
"danifica a cortina.")
return
}
caSend(cmd)
}
 
def toggleAll() {
String mac = state.macFmt
if (!mac) { logWarn("[CMD] toggleAll sem MAC — rodando getmac."); getmac(); return }
List luz = canaisDeLuz()
if (!luz) {
logWarn("[CMD] toggleAll: caixa sem saída de iluminação — não se aplica.")
return
}
int mask = 0
luz.each { int ch -> mask |= (1 << ch) }
caSend("mdcmd_mtogglerele,${mac},${mask}")
}



void handleLine(String line) {
if (line.startsWith("macaddr")) {
String mac = caExtractMac(line)
if (mac) {
state.macFmt = mac
sendEvent(name: "macaddr", value: mac)
logInfo("[RX] MAC da placa: ${mac}")
runInMillis(150, "getstatus")
} else {
logWarn("[RX] Não extraí 3 bytes de MAC de: '${line}'")
}
return
}

if (line ==~ /\d{3,6}/) {
sendEvent(name: "firmware", value: String.format("%.3f", (line as Integer) / 1000.0d))
return
}
if (line.startsWith("setcmd,")) {
String[] f = line.split(",")
if (f.size() >= 2 + MAX_INPUTS + MAX_OUTPUTS) {
updateIO(f)
} else {
logDebug("[RX] setcmd com ${f.size()} campos (esperado ≥${2 + MAX_INPUTS + MAX_OUTPUTS}) — ignorado")
}
return
}
if (line.equalsIgnoreCase("MasterOn") || line.equalsIgnoreCase("MasterOff")) {
runInMillis(150, "getstatus")
return
}
logDebug("[RX] Linha não tratada: ${line}")
}
 
private String caExtractMac(String line) {
List<String> pares = (line =~ /[0-9A-Fa-f]{2}/).collect { it.toString().toUpperCase() }
if (pares.size() < 3) return null
return pares[-3..-1].collect { "0x${it}" }.join(",")
}



private void updateIO(String[] f) {
int inStart = 2
int outStart = inStart + MAX_INPUTS
long nowTs = now()
boolean pulse = (settings.inputsPulseMode != false)
long debounce = ((settings.pulseDebounceMs ?: 50) as long)
long release = ((settings.pulseReleaseMs ?: 150) as long)

for (int i = 0; i < ((state.inCount ?: MAX_INPUTS) as int); i++) {
int cur = (f[inStart + i]?.trim() == "1") ? 1 : 0
int prev = ((state.prevInputs[i] ?: 0) as int)
if (cur == prev) continue
if (pulse) {
if (cur == 1) { 
long lastTs = ((state.lastPulseTs[i] ?: 0L) as long)
if (nowTs - lastTs >= debounce) {
sendEvent(name: "pushed", value: i + 1, isStateChange: true, type: "digital",
descriptionText: "${device.displayName} entrada ${i + 1} pulsada")
setContactChild(i + 1, "closed")
runInMillis((int) release, "releaseInput",
[overwrite: false, data: [input: i + 1]]) 

state.lastPulseTs[i] = nowTs
}
} else { 
setContactChild(i + 1, "open")
}
} else {
setContactChild(i + 1, cur == 1 ? "closed" : "open")
}
state.prevInputs[i] = cur
}



Set motoresOut = motorOuts()
boolean anyOn = false
for (int j = 0; j < ((state.outCount ?: MAX_OUTPUTS) as int); j++) {
String sw = (f[outStart + j]?.trim() == "1") ? "on" : "off"
if (sw == "on" && !motoresOut.contains(j)) anyOn = true
def cd = getChildDevice(outDni(j + 1))
if (cd && cd.currentValue("switch") != sw) {
cd.parse([[name: "switch", value: sw, descriptionText: "${cd.displayName} ${sw}"]])
}
}



updateParentSwitch(anyOn, canaisDeLuz(motoresOut).isEmpty())












Map ultimoBits = (state.coverBitState instanceof Map) ? (state.coverBitState as Map) : [:]
((state.covers ?: []) as List).each { Map c ->
int i = (c.i as Integer)
int idxSobe = i * 2
int idxDesce = i * 2 + 1



if (idxDesce >= MAX_OUTPUTS) {
logError("[CORTINA] motor ${i} exige as saídas ${idxSobe}/${idxDesce}, fora do teto de " +
"${MAX_OUTPUTS} -- ignorado neste setcmd.")
return
}
int sobe = (f[outStart + idxSobe]?.trim() == "1") ? 1 : 0
int desce = (f[outStart + idxDesce]?.trim() == "1") ? 1 : 0
String estado = estadoCortina(sobe, desce)
String chave = "${i}"
if (estado == ultimoBits[chave]) return 
ultimoBits[chave] = estado
def cd = getChildDevice(coverDni(i))
if (estado == "opening" || estado == "closing") {
armarCurso(i)
if (cd) cd.parse([[name: "windowShade", value: estado, descriptionText: "${cd.displayName} ${estado}"]])
} else {
cancelarCurso(i)











if (cd) {
Map ot = (state.coverOtimista instanceof Map) ? (state.coverOtimista as Map) : [:]
String valor = (estado == "unknown") ? "unknown" : ((ot[chave] as String) ?: "partially open")
cd.parse([[name: "windowShade", value: valor, descriptionText: "${cd.displayName} ${valor}"]])
}
}
}
state.coverBitState = ultimoBits
}
 
private String estadoCortina(int sobe, int desce) {
if (sobe && desce) return "unknown" 
if (sobe) return "opening"
if (desce) return "closing"
return null
}
 
private void armarCurso(int i) {
Map gens = (state.cursoGen instanceof Map) ? (state.cursoGen as Map) : [:]
int gen = ((gens["${i}"] ?: 0) as int) + 1
gens["${i}"] = gen
state.cursoGen = gens
runIn((settings.tempoCursoS ?: 60) as Integer, "cursoEstourou",
[data: [i: i, gen: gen], overwrite: false])
}
private void cancelarCurso(int i) {
Map gens = (state.cursoGen instanceof Map) ? (state.cursoGen as Map) : [:]
gens["${i}"] = ((gens["${i}"] ?: 0) as int) + 1
state.cursoGen = gens
}
def cursoEstourou(Map data) {
Integer i = data?.i as Integer
if (i == null) return
Map gens = (state.cursoGen instanceof Map) ? (state.cursoGen as Map) : [:]
if ((data?.gen as Integer) != ((gens["${i}"] ?: 0) as Integer)) return 
def cd = getChildDevice(coverDni(i))
cd?.parse([[name: "windowShade", value: "unknown"]])
logWarn("[CORTINA] motor ${i} não parou dentro de ${settings.tempoCursoS ?: 60}s " +
"— estado publicado como 'unknown'. Nenhum comando foi enviado ao motor.")
}
 
def releaseInput(Map data) {
Integer n = data?.input as Integer
if (n) setContactChild(n, "open")
}
private void setContactChild(int n, String value) {
if (!settings.inputsCreateContactChildren) return
def cd = getChildDevice(inDni(n))
if (cd && cd.currentValue("contact") != value) {
cd.parse([[name: "contact", value: value, descriptionText: "${cd.displayName} ${value}"]])
}
}



private String outDni(int n) { return "${device.id}-${OUT_PREFIX}-${n}" }
private String inDni(int n) { return "${device.id}-${IN_PREFIX}-${n}" }
private void createChildren() {
int outs = ((state.outCount ?: MAX_OUTPUTS) as int)
Set motores = motorOuts()
for (int i = 1; i <= outs; i++) {
if (motores.contains(i - 1)) continue 
if (!getChildDevice(outDni(i))) {
addChildDevice("hubitat", CHILD_SWITCH, outDni(i),
[name: "${device.displayName} Relé ${i}", isComponent: true])
}
}

for (int k = outs + 1; k <= MAX_OUTPUTS; k++) {
if (getChildDevice(outDni(k))) deleteChildDevice(outDni(k))
}
if (settings.inputsCreateContactChildren) {
int ins = ((state.inCount ?: MAX_INPUTS) as int)
for (int j = 1; j <= ins; j++) {
if (!getChildDevice(inDni(j))) {
addChildDevice("hubitat", "Generic Component Contact Sensor", inDni(j),
[name: "${device.displayName} Entrada ${j}", isComponent: true])
}
}
for (int m = ins + 1; m <= MAX_INPUTS; m++) {
if (getChildDevice(inDni(m))) deleteChildDevice(inDni(m))
}
} else {
(1..MAX_INPUTS).each { int j ->
def cd = getChildDevice(inDni(j))
if (cd) deleteChildDevice(inDni(j))
}
}
}
 
private void updateParentSwitch(boolean anyOn, boolean semLuz) {
if (semLuz) return
String sw = anyOn ? "on" : "off"
if (device.currentValue("switch") != sw) {
sendEvent(name: "switch", value: sw, descriptionText: "${device.displayName} ${sw}")
}
}



void componentRefresh(cd) { getstatus() }
void componentOn(cd) { componentSwitch(cd, 1) }
void componentOff(cd) { componentSwitch(cd, 0) }
private void componentSwitch(cd, int val) {
Integer ch = channelFromDni(cd.deviceNetworkId)
if (ch == null) { logError("[CMD] DNI inesperado: ${cd.deviceNetworkId}"); return }



if (motorOuts().contains(ch - 1)) {
logError("[CMD] RECUSADO: saída ${ch} é perna de par de motor (cortina). " +
"Use o filho de cortina; rode limparOrfaos() para remover este botão.")
return
}
String mac = state.macFmt
if (!mac) { logWarn("[CMD] Sem MAC — comando descartado; rodando getmac."); getmac(); return }
caSend("mdcmd_sendrele,${mac},${ch - 1},${val}") 
}
 
private Integer channelFromDni(String dni) {
def m = (dni =~ /-${OUT_PREFIX}-(\d+)$/)
return m.find() ? (m.group(1) as Integer) : null
}

 
private Integer motorIndexFromDni(String dni) {
def m = (dni =~ /-COVER-(\d+)$/)
return m.find() ? (m.group(1) as Integer) : null
}
void componentOpen(cd) { cortinaCmd(cd, 2, 0, "open", false, 100) }
void componentClose(cd) { cortinaCmd(cd, 2, 2, "closed", false, 0) }
 
void componentStopPositionChange(cd) { cortinaCmd(cd, 2, 1, "partially open", true) }
void componentSetPosition(cd, pos) {
Integer p = Math.max(0, Math.min(100, (pos ?: 0) as Integer))
String estado = (p == 0) ? "closed" : (p == 100) ? "open" : "partially open"
cortinaCmd(cd, 0, levelToRaw(p), estado, false, p) 
}
 
void componentStartPositionChange(cd, direction) {
cortinaCmd(cd, 2, (direction == "close") ? 2 : 0, "partially open")
}
 
private static int levelToRaw(int level) {
int l = Math.max(0, Math.min(100, level))
return (int) Math.round(l * 255.0d / 100.0d)
}
 
private void cortinaCmd(cd, int registrador, int valor, String windowShade, boolean stop = false, Integer posicao = null) {
Integer i = motorIndexFromDni(cd.deviceNetworkId as String)
if (i == null) { logError("[CMD] DNI de cortina inesperado: ${cd.deviceNetworkId}"); return }
String mac = state.macFmt
if (!mac) { logWarn("[CMD] Sem MAC — comando descartado; rodando getmac."); getmac(); return }
if (!caSend("mdcmd_sendcmd,${mac},${registrador},${valor},${i}")) return





if (stop) cancelarCurso(i)



def filho = getChildDevice(coverDni(i))
if (filho && windowShade) {
List eventos = [[name: "windowShade", value: windowShade,
descriptionText: "${filho.displayName} ${windowShade} (otimista)"]]
if (posicao != null) {
eventos << [name: "position", value: posicao,
descriptionText: "${filho.displayName} position ${posicao} (otimista)"]
}
filho.parse(eventos)
}
if (windowShade in ["open", "closed", "partially open"]) {
Map ot = (state.coverOtimista instanceof Map) ? (state.coverOtimista as Map) : [:]
ot["${i}"] = windowShade
state.coverOtimista = ot
}
sendEvent(name: "feedback", value: "otimista",
descriptionText: "cortina motor ${i}: comando enviado, sem confirmação de posição real")
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
if (v < 2 || v > 3) throw new IllegalStateException(
"'${nome}' é da versão ${art.v}; este driver lê a 2 e a 3 — atualize o driver ou regere o arquivo")
if (!(art.gateways instanceof List) || !art.gateways) throw new IllegalStateException(
"'${nome}' não tem nenhuma central em 'gateways'")
return art
}
 
private Map escolherBlocoMcrl(Map art, String nome) {
List gws = ((art.gateways ?: []) as List).findAll { it.type == "mcrl" }
if (!gws) throw new IllegalStateException(
"'${nome}' não tem nenhum bloco de caixa cabeada (type: mcrl)")
String meu = ((settings.device_IP_address ?: "") as String).trim()
if (!meu) {
if (gws.size() == 1) return (Map) gws[0]
throw new IllegalStateException(
"preencha o IP desta caixa — '${nome}' tem ${gws.size()}: " +
gws.collect { it.ip }.join(", "))
}
List casam = gws.findAll { (((it.ip ?: "") as String).trim()) == meu }
if (casam.size() == 1) return (Map) casam[0]
if (!casam) throw new IllegalStateException(
"nenhuma caixa com IP ${meu} em '${nome}' — o arquivo tem: " +
gws.collect { it.ip }.join(", "))
throw new IllegalStateException(
"'${nome}' tem ${casam.size()} caixas com o MESMO IP ${meu} — arquivo malformado")
}
 
private void setImportStatus(String st, String detalhe) {
sendEvent(name: "importStatus", value: st)
sendEvent(name: "importDetail", value: detalhe)
}
def importarDoProjeto() {
String nome = ((settings.moduleFile ?: "") as String).trim()
if (!nome) { logError("[IMPORT] Nenhum arquivo de import configurado."); return }
try {
Map art = lerArtefato(nome)
Map bloco = escolherBlocoMcrl(art, nome)
state.covers = ((bloco.covers ?: []) as List).collect {
[i: (it.i as Integer), label: (it.label ?: "") as String,
room: (it.room ?: "") as String] }
state.relays = ((bloco.relays ?: []) as List).collect {
[ch: (it.ch as Integer), label: (it.label ?: "") as String,
room: (it.room ?: "") as String] }
state.lastImportFile = nome




state.redeTemCortina = ((art.gateways ?: []) as List).any { it.type == "mcrl" && ((it.covers ?: []) as List) }





String meu = ((settings.device_IP_address ?: "") as String).trim()
String doArq = ((bloco.ip ?: "") as String).trim()
String aviso = null
if (!meu && doArq) {
device.updateSetting("device_IP_address", [value: doArq, type: "text"])
if (bloco.port) device.updateSetting("device_port", [value: (bloco.port as Integer), type: "number"])
logInfo("[IMPORT] IP preenchido pelo arquivo: ${doArq}:${bloco.port ?: 4998}")
} else if (meu && doArq && meu != doArq) {
aviso = "o arquivo diz ${doArq}, este device está em ${meu} — confira qual é o certo; IP NÃO sobrescrito"
logWarn("[IMPORT] ${aviso}")
}
Map resultado = aplicarFilhos()






String detalhe = "${state.covers.size()} cortina(s) e ${state.relays.size()} " +
"relé(s) no arquivo '${nome}' — ${resultado.criados} novo(s), " +
"${resultado.existentes} já existia(m)"
if (state.orfaos) {






int mant = (orfaosContraArquivo(nome)?.mantidosPorRecriacao as List)?.size() ?: 0
int remov = Math.max(0, state.orfaos.size() - mant)
detalhe += " · ${state.orfaos.size()} órfão(s) (nenhum removido)"
if (remov) detalhe += " — ${remov} sai(em) com limparOrfaos()"
if (mant) detalhe += (remov ? ";" : " —") + " ${mant} volta(m) sozinho(s) no próximo import"
}
if (aviso) detalhe += " · ${aviso}"
logInfo("[IMPORT] ${detalhe}")
setImportStatus(aviso ? "aviso" : "ok", detalhe)
} catch (Exception e) {
String motivo = e.message ?: e.toString()
logError("[IMPORT] ${motivo}")
setImportStatus("erro", motivo)
}
}
 
private Set motorOuts() {
Set out = [] as Set
((state.covers ?: []) as List).each { Map c ->
int i = (c.i as Integer)
out << (i * 2)
out << (i * 2 + 1)
}
return out
}
 
private String coverDni(int i) { return "${device.id}-COVER-${i}" }
 
private Map aplicarFilhos() {



state.orfaos = []
Set motores = motorOuts()
List esperados = []
int criados = 0
((state.relays ?: []) as List).each { Map r ->
int ch = (r.ch as Integer)
if (motores.contains(ch)) {
logError("[IMPORT] saída ${ch + 1} está em `relays` E ocupada por par de motor — " +
"ignorada. Confira a combinação de saídas no mdConfig.")
return
}



String dni = outDni(ch + 1)
esperados << dni
def cd = getChildDevice(dni)
if (!cd) {
addChildDevice("hubitat", CHILD_SWITCH, dni,
[name: "${device.displayName} Relé ${ch + 1}", isComponent: true,
label: rotuloDe(r)])
logInfo("[CHILD] Criado ${dni} (relé) — ${rotuloDe(r)}")
criados++
} else if (!cd.label && rotuloDe(r)) {



cd.setLabel(rotuloDe(r))
logInfo("[CHILD] ${dni} rotulado pelo projeto — ${rotuloDe(r)}")
}
}
((state.covers ?: []) as List).each { Map c ->
int i = (c.i as Integer)
String dni = coverDni(i)
esperados << dni
def cd = getChildDevice(dni)
if (!cd) {
addChildDevice("hubitat", CHILD_SHADE, dni,
[name: "${device.displayName} Cortina ${i + 1}", isComponent: true,
label: rotuloDe(c)])
logInfo("[CHILD] Criado ${dni} (cortina) — ${rotuloDe(c)}")
criados++
} else if (!cd.label && rotuloDe(c)) {
cd.setLabel(rotuloDe(c))
logInfo("[CHILD] ${dni} rotulado pelo projeto — ${rotuloDe(c)}")
}
}



List candidatos = getChildDevices()
.findAll { it.deviceNetworkId.contains("-${OUT_PREFIX}-") || it.deviceNetworkId.contains("-COVER-") }
.collect { it.deviceNetworkId }
List orfaos = candidatos.findAll { !esperados.contains(it) }
state.orfaos = orfaos
if (orfaos) {
logError("[IMPORT] ${orfaos.size()} filho(s) não são saída(s) de iluminação/cortina " +
"neste arquivo (${orfaos.join(', ')}): nenhum foi removido. Rode limparOrfaos() " +
"se a remoção for intencional.")
}







device.deleteCurrentState("switch")





if (((state.covers ?: []) as List)) {
sendEvent(name: "feedback", value: "otimista",
descriptionText: "cortina cabeada: o estado publicado é o comandado — " +
"o MCRL2 não confirma posição real")
}
return [criados: criados, existentes: (esperados.size() - criados)]
}
 
private String rotuloDe(Map x) {
String l = ((x.label ?: "") as String).trim()
String r = ((x.room ?: "") as String).trim()
if (!l) return r ?: null
return r ? "${r} — ${l}" : l
}
 
private Map orfaosContraArquivo(String nome) {
Map bloco = escolherBlocoMcrl(lerArtefato(nome), nome)
Set motoresDoArquivo = [] as Set
((bloco.covers ?: []) as List).each { Map c ->
int i = (c.i as Integer)
motoresDoArquivo << (i * 2)
motoresDoArquivo << (i * 2 + 1)
}
List validos = []


((bloco.relays ?: []) as List).each {
int ch = (it.ch as Integer)
if (!motoresDoArquivo.contains(ch)) validos << outDni(ch + 1)
}
((bloco.covers ?: []) as List).each { validos << coverDni(it.i as Integer) }
List candidatos = getChildDevices().findAll {
it.deviceNetworkId.contains("-${OUT_PREFIX}-") || it.deviceNetworkId.contains("-COVER-")
}.collect { it.deviceNetworkId }
List foraDoArquivo = candidatos.findAll { !validos.contains(it) }








Set motores = motorOuts()
int outs = (state.outCount ?: MAX_OUTPUTS) as int
List orfaos = []
List mantidosPorRecriacao = []
foraDoArquivo.each { dni ->
Integer ch = channelFromDni(dni)
boolean recriavel = ch != null && !motores.contains(ch - 1) && ch <= outs
if (recriavel) { mantidosPorRecriacao << dni } else { orfaos << dni }
}
return [bloco: bloco, motoresDoArquivo: motoresDoArquivo, validos: validos,
candidatos: candidatos, orfaos: orfaos, mantidosPorRecriacao: mantidosPorRecriacao,
foraDoArquivo: foraDoArquivo]
}
 
def conferirProjeto() {
String nome = ((settings.moduleFile ?: "") as String).trim()
if (!nome) { logError("[CONFERIR] Nenhum arquivo de import configurado."); return }
try {
Map r = orfaosContraArquivo(nome)
Map bloco = r.bloco as Map
Set motoresDoArquivo = r.motoresDoArquivo as Set
int div = 0
((bloco.relays ?: []) as List).each {
int ch = (it.ch as Integer)



if (motoresDoArquivo.contains(ch)) return
if (!getChildDevice(outDni(ch + 1))) {
logWarn("[CONFERIR] relé '${it.label ?: (ch + 1)}' (saída ${ch + 1}) está no arquivo " +
"e não no hub — rode importarDoProjeto().")
div++
}
}
((bloco.covers ?: []) as List).each {
int i = (it.i as Integer)
if (!getChildDevice(coverDni(i))) {
logWarn("[CONFERIR] cortina '${it.label ?: i}' (motor ${i}) está no arquivo " +
"e não no hub — rode importarDoProjeto().")
div++
}
}
((r.orfaos ?: []) as List).each {
logWarn("[CONFERIR] ${it} está no hub e não no arquivo (rode limparOrfaos() se for intencional).")
div++
}




((r.mantidosPorRecriacao ?: []) as List).each {
logWarn("[CONFERIR] ${it} está fora de '${nome}', mas é recriado pela preferência 'outputs' -- " +
"não conta como divergência (reduza 'outputs' para removê-lo de verdade).")
}
String detalhe = "${div} divergência(s) contra '${nome}'."
logInfo("[CONFERIR] ${detalhe}")
setImportStatus(div ? "aviso" : "ok", detalhe)
} catch (Exception e) {
String motivo = e.message ?: e.toString()
logError("[CONFERIR] ${motivo}")
setImportStatus("erro", motivo)
}
}
 
def limparOrfaos() {
String nome = ((settings.moduleFile ?: "") as String).trim()
if (!nome) { logError("[ÓRFÃO] Nenhum arquivo de import configurado."); return }
Map r
try {
r = orfaosContraArquivo(nome)
} catch (Exception e) {
String motivo = e.message ?: e.toString()
logError("[ÓRFÃO] ${motivo}")
setImportStatus("erro", motivo)
return
}
List candidatos = r.candidatos as List
List orfaos = r.orfaos as List
List foraDoArquivo = r.foraDoArquivo as List









if (candidatos && foraDoArquivo.size() == candidatos.size()) {




String motivo = "'${nome}' resultaria em remover TODOS os ${candidatos.size()} filho(s) desta " +
"caixa -- arquivo truncado, bloco vazio, ou limparOrfaos() rodado ANTES do " +
"importarDoProjeto()? Nada foi apagado; rode importarDoProjeto() primeiro e " +
"confira o arquivo."
logError("[ÓRFÃO] ${motivo}")
setImportStatus("erro", motivo)
return
}
List mantidos = r.mantidosPorRecriacao as List
int n = 0
orfaos.each { dni ->
logWarn("[ÓRFÃO] Removendo ${dni} — não está em '${nome}'")
deleteChildDevice(dni)
n++
}





mantidos.each { dni ->
Integer ch = channelFromDni(dni)
logWarn("[ÓRFÃO] ${dni} (saída ${ch}) não está em '${nome}', mas foi MANTIDO -- a preferência " +
"'outputs' recria este filho no próximo initialize()/Save Preferences; para removê-lo de " +
"verdade, reduza 'outputs' abaixo de ${ch} (ou inclua a saída no arquivo).")
}
state.orfaos = []



String detalhe = (n || mantidos)
? "${n} filho(s) removido(s) a partir de '${nome}'" +
(mantidos ? "; ${mantidos.size()} mantido(s) por recriação automática da preferência 'outputs' " +
"(reduza-a para remover de verdade)" : "") + "." +
(n ? " Rode importarDoProjeto() novamente para realinhar state.covers/relays e limpar timers " +
"pendentes de cortina removida." : "")
: "Nenhum filho removido — hub já bate com '${nome}'."
logInfo("[ÓRFÃO] ${detalhe}")
setImportStatus("ok", detalhe)
}
