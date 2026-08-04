# ControlArt 7Port — Hubitat (TecnoSimples)

Integração local entre Hubitat e centrais **ControlART** (7Port, xPort, xBus,
módulos IP de relé/dimmer, IR e RF).
**Produto licenciado da TecnoSimples Tecnologia LTDA.**

## Instalação (via Hubitat Package Manager)

1. No HPM, escolha **Install** → **From a URL**.
2. Cole a URL do **packageManifest.json** (atenção: é o manifesto, NÃO o repository.json):
   `https://raw.githubusercontent.com/tecnosimples/hubitat_controlart/main/packageManifest.json`
3. Avance — deve aparecer **"You are about to install ControlArt 7Port"**. Conclua (o HPM baixa todos os drivers).

> Se aparecer **"install null"**, você colou a URL errada na opção errada. A opção **"From a URL"** espera o **`packageManifest.json`**. O `repository.json` só serve para a opção **"Add a Custom Repository" → "Browse by Tags"**.

**Não existe biblioteca a instalar.** Cada driver deste pacote já vem completo.
Se você instalou esta suíte à mão antes, a ordem "biblioteca primeiro" e o
re-salvar todo driver a cada atualização **deixam de valer** — o HPM atualiza
os arquivos juntos.

## Qual driver para quê

| Driver | Para |
|---|---|
| **ControlArt xPort Gateway** | a central (7Port / xPort) — descobre os módulos do barramento |
| **ControlArt Xbus Relay** / **Xbus Dimmer** | módulos do barramento xBus |
| **ControlArt Ethernet Relay** / **Ethernet Dimmer** | módulos IP (conectam no IP da própria placa) |
| **ControlArt IR TV e Som** | TV, som, receiver |
| **ControlArt IR AC** | ar-condicionado |
| **ControlArt RF Cortinas** | persiana / cortina |

## Configuração

1. Em **Devices** → **Add Device** → **Virtual**, escolha o driver e dê um nome.
2. Nas **Preferences**, preencha o **IP da placa** e a **Porta TCP** (padrão ControlART: `4998`)
   e clique em **Save Preferences**. Nos drivers de IR há também o **canal IR de saída (1-8)**.
3. Leia o atributo **`licenca`** na página do device e envie o **HubUID** exibido para
   **contato@tecnosimples.com.br** para liberar sua licença (uma por hub; não há chave para digitar).

> Novos hubs ganham um **trial automático**. Sem licença válida (ativa ou trial) o driver
> não envia comandos à central. O hub precisa de internet para validar; depois disso
> funciona localmente e revalida sozinho.

## Os dois atributos que dizem se está tudo bem

- **`boardstatus`** — `online` quando a placa está respondendo; vira `offline` sozinho se ela cair.
- **`licenca`** — `ativa`, `trial — vence <data>` ou `inativa`.

## Suporte
TecnoSimples Tecnologia LTDA · contato@tecnosimples.com.br · (14) 99760-6885
