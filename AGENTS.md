# REGRAS — Constituição TecnoSimples (v6)

> Núcleo cross-tool, comum a todas as IAs. As regras específicas de cada IA estão no seu delta (`CLAUDE.md`; o Grok lê este núcleo direto); as regras deste projeto, em `notes/PROJECT_RULES.md`.

## 📌 TLDR — Os 6 Imperativos
1. **Abertura (se for repo git):** `git status` + `git fetch` + `git log origin/main` antes de editar.
2. **Durante:** commits e pushes para `origin` (GitHub) são livres — proteja o trabalho.
3. **Fechamento:** `/handoff` (tracker, changelog, decisões, commit + push) antes de declarar "pronto".
4. **Backup:** obrigatório antes de qualquer operação destrutiva (REGRA ZERO).
5. **Deploy ≠ push:** produção nunca sem autorização explícita do usuário.
6. **Fronteira:** escrita só dentro do workspace aberto — fora dele, só com autorização do usuário ou liberdade declarada no `PROJECT_RULES.md` (e sempre avisando).

## 🚀 Protocolo de Início
Antes de agir, leia e **declare o resumo**:
1. `notes/PROJECT_BRAIN.md` — stack, arquitetura, decisões, gotchas.
2. `notes/TASK_TRACKER.md` — `## [PONTO_DE_RETOMADA]` é a âncora.
3. `notes/CHANGELOG.md` — última entrada.
4. `notes/PROJECT_RULES.md` — regras específicas deste projeto.

Para resgatar decisões que saíram do contexto: `rg "cat: <categoria>" notes/DECISOES.md` (caminhos já tentados) e `rg` em `notes/` e `notes/archive/` (inclui o legado do cérebro em `notes/archive/cerebro/`). Entre projetos: `rg` sobre `IA_Base/*/notes/`.
**Declaração:** "Contexto carregado — [estado do TASK_TRACKER em 1 linha]. Pronto."

## ⚠️ REGRA ZERO — Backup Antes de Destruir
Antes de comandos destrutivos (`docker compose down -v`, `rm -rf` em dados/código, `DROP`/`TRUNCATE`/`DELETE` sem `WHERE`, reset/rollback de banco ou migrations):
1. Verifique em `notes/PROJECT_BRAIN.md` o comando de backup do projeto.
2. Execute o backup ou um snapshot (`git commit`). Se não for possível, **PARE, avise e aguarde.**

## 🧱 Confinamento de Workspace
O workspace aberto (a pasta do projeto) é a **fronteira de escrita** da sessão. Sem **delegação, autorização ou confirmação explícita do usuário**, nenhum modelo cria, modifica ou apaga arquivos fora dela — em nenhuma hipótese. Leitura para consulta é livre (`_knowledge/`, `ManualMarca/`, docs de outros projetos); a fronteira vale para **escrita**.
- **Liberdades pré-declaradas:** o `notes/PROJECT_RULES.md` do próprio workspace pode listar outros workspaces/paths liberados para escrita (seção "Liberdades entre workspaces"). Mesmo usando uma liberdade declarada, **avise o usuário** do que foi tocado fora.
- **Consulta entre projetos sempre livre:** ler `IA_Base/*/notes/` (`rg`) é permitido de qualquer workspace, sobre qualquer assunto. Os fluxos mandatados por esta constituição (`/handoff`: commit + push do próprio repo) contam como pré-autorizados.

## 🥇 Prioridade entre Regras
Em conflito, siga a ordem: (1) **Segurança, backup e fronteira** (REGRA ZERO + Confinamento de Workspace); (2) **Não mude o que não foi pedido**; (3) **Comportamento de ambiguidade** (definido no delta da IA).

## 🛠️ Desenvolvimento
1. **Simplicidade:** o mínimo que resolve. Sem features, abstrações ou scaffolding "para depois" além do pedido. Sem interface com uma implementação, sem config para valor que nunca muda.
2. **Escada antes de escrever código novo** (pare no primeiro degrau que resolve): (a) precisa existir? (b) já existe neste codebase? reuse; (c) stdlib faz? (d) recurso nativo da plataforma cobre? (`<input type="date">` em vez de lib, CSS em vez de JS, constraint no banco em vez de código); (e) dependência já instalada resolve? — **nunca adicione dependência nova para o que cabe em poucas linhas**; (f) só então, código próprio mínimo.
3. **Mude só o necessário:** não refatore código vizinho; combine o estilo existente; cada linha alterada rastreia ao pedido. Menor diff que funciona, no lugar certo — o menor diff no lugar errado é um segundo bug.
4. **Declare antes de codar** (tarefas com >1 arquivo ou >10 linhas): liste 2-3 abordagens, diga qual vai usar e por quê, então escreva. Correção trivial pula esta etapa.
5. **Leia antes de escrever:** trace o fluxo real ponta a ponta antes de escolher a solução; não duplique o que já existe no arquivo.
6. **Bug = causa raiz, não sintoma:** antes de editar uma função, `rg` em todos os callers. Um guard na função compartilhada é menor e mais correto que um guard em cada caller.
7. **Nunca simplifique** validação em fronteira de confiança, tratamento de erro que evita perda de dados, segurança, acessibilidade básica, nem o que foi explicitamente pedido. Em hardware, deixe o knob de calibração (clock real deriva, sensor real lê torto).
8. **Marque o teto:** simplificação deliberada com limite conhecido (lock global, scan O(n²), heurística ingênua) leva comentário `simplificado: <teto>, <upgrade quando>`.
9. **Um check executável:** lógica não trivial (branch, loop, parser, caminho de dinheiro/segurança) deixa um teste mínimo que falha se ela quebrar — um `assert` em `__main__` ou um `test_*.py` pequeno. Sem framework nem suíte por função se não pedido; one-liner trivial não precisa.
10. **Falhe alto:** erro não tratado = falha. Nada de "sucesso" com etapas puladas em silêncio.
11. **Mini-checkpoint:** ao concluir um marco (item de TODO, decisão, milestone), atualize `notes/TASK_TRACKER.md` e registre. Não espere o handoff para anotar progresso.

## 🔄 Git — Sincronia
GitHub (`origin`) é a fonte de verdade entre máquinas e IAs. *(Pule esta seção se o diretório não for um repo git.)*
- **Abertura:** `git status` → `git fetch origin` → `git log --oneline -5 origin/main`. Local atrás → `git pull`; **local na frente ou divergido → PARE e reporte** (nunca `pull` cego nem `--force`).
- **Durante:** commits pequenos e frequentes, padrão `tipo: descrição` (`feat`, `fix`, `docs`, `chore`, `refactor`). **Nunca `git add .`/`-A`** (vaza `.env`, segredos, `node_modules`, builds) — sempre paths explícitos.
- **Fechamento:** `git status` → `git push origin main` → confirmar sincronia com o remoto.
- **Deploy ≠ push:** `git push origin main` **não** deploya. Deploy (`push production`, `deploy.sh`, SSH em VPS, subir build a hosting) exige palavra-gatilho: "deploy", "subir para produção", "publicar". Sem ela, PARE e pergunte.
- Nunca `--force`/`--amend`/`--no-verify` em commits já pushados.
- **Branches `wip/<máquina>`** são rede de segurança automática (trabalho que ficou sem `/handoff` noutra máquina). Ao encontrar um na abertura, **avise o usuário e ofereça aplicar**; nunca apague nem sobrescreva sem perguntar.

## 🔒 Segurança
- Segredos só em variáveis de ambiente — nunca hardcoded, nunca logados (mesmo em debug).
- Todo repositório GitHub TecnoSimples é criado **sempre `--private`**, sem exceção.

## ↩️ Rollback
Se uma mudança quebrar algo: pare, avise com clareza (o quê e onde) e prefira `git revert` a empilhar "fixes" sucessivos.

## 🧠 Memória — quatro arquivos por projeto, no git
Toda memória de domínio vive em `notes/`, versionada junto do código — nunca em caches paralelos de IA nem em banco externo. (O cérebro TecnoMnemoSimples foi **congelado em 2026-09-02**; o legado está em `notes/archive/cerebro/` de cada repo, consultável com `rg`.)

| Arquivo | Responde | Quando escrever |
|---|---|---|
| `TASK_TRACKER.md` | Onde parei | `/handoff` e a cada marco |
| `PROJECT_BRAIN.md` | Como funciona | Só mudança estrutural |
| `DECISOES.md` | O que não repetir | `/handoff`: 1 linha por caminho tentado e descartado |
| `CHANGELOG.md` + `archive/` | O que foi feito | `/handoff` |

- **Handoff** (gatilhos "sair"/"pausar"/"fechar" ou `/handoff`): atualize o `## [PONTO_DE_RETOMADA]`, registre no `CHANGELOG.md` com timestamp, atualize `PROJECT_BRAIN.md` se houve decisão estrutural, acrescente ao `DECISOES.md` o que foi tentado e descartado, e execute o rito git de fechamento.
- **Formato do `[PONTO_DE_RETOMADA]` (painel de Portfólio — texto fiel, sem IA):** o gerador copia esse bloco **literal** (corta ~500 chars) e lista `- [ ]` / `⚠️` como pendências. Escreva **direto e curto** (3–6 linhas úteis), não um relatório de sessão:
  1. **Uma linha** de estado (o que está pronto / em prod / bloqueado).
  2. **Próximo passo** em 1–3 bullets acionáveis (`- [ ] …` se for pendência real).
  3. **Evite** no bloco vivo: tabelas longas, hashes de commit, histórico de sessões, dumps de deploy. Histórico vai **abaixo** do bloco (outro heading) ou no CHANGELOG.
  Exemplo mínimo:
  ```
  ## [PONTO_DE_RETOMADA]
  Calendar e prioridade do Portfólio em prod. Validado no app.
  - [ ] Converter cliente potencial (QA em prod)
  - [ ] QA fluxo pendência ponta a ponta (origem → desfecho ADM)
  ```
- **`DECISOES.md` (anti-loop):** append-only, mais recentes primeiro, formato `- DATA · cat: CATEGORIA · tentei X · deu Y · não repetir: Z`. Registra o **caminho tentado**, não só o veredito. **Antes de propor um caminho**, `rg "cat: <categoria>" notes/DECISOES.md`. Passou de ~150 linhas → metade antiga para `notes/archive/DECISOES.md` (arquivar, nunca apagar).
- **CHANGELOG preservado:** mantenha enxuto (~5 entradas ativas); arquive as antigas em `notes/archive/` — **não apague**.

## ⏳ Economia de Sessão
Sessão curta, **um foco por vez**. Micro-handoff frequente (manter `notes/` em dia) barateia descartar e recarregar contexto. Os comandos específicos de cada ferramenta para isso estão no delta da IA.

## 🏁 Capacidades da IA
As capacidades, o modo de operação e o comportamento de ambiguidade de cada IA estão no respectivo delta (`CLAUDE.md`), carregado junto deste núcleo. O Grok lê `AGENTS.md` nativamente. Gemini/Antigravity foi aposentado em 2026-09-17.
