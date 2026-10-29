# GodHand-CRF1-RECOMP

Projeto experimental de port do God Hand (PS2) para Android ARM64, baseado no código público do [GodHandAndroidPort](https://github.com/owaissseeee/GodHandAndroidPort) e na recompilação estática de [LasVola/godhand-recomp](https://github.com/LasVola/godhand-recomp).

## Estado atual

A integração do caminho Android foi conectada no código, mas **a nova integração ainda precisa passar por uma compilação manual e por teste em dispositivo**. Não considere o jogo funcional até confirmar que o runtime inicializa e apresenta a imagem do jogo.

O que foi conectado nesta etapa:

- Launcher Kotlin com seleção da ISO pelo Storage Access Framework e permissão persistente de leitura.
- Verificação nativa inicial da ISO9660 e dos arquivos `SYSTEM.CNF` e `SLUS_215.03`.
- Extração nativa dos arquivos da ISO para o armazenamento privado do aplicativo (`files/godhand_cache`), com progresso no launcher e log exportável.
- Atividade Android com `SurfaceView` para fornecer uma superfície nativa ao backend gráfico.
- CMake configurado para integrar a árvore Android completa da referência: runtime EE/IOP, áudio, PCSX2 GS, bridge JNI, código recompilado do ELF principal e os 81 overlays SNR2.
- Prefixos JNI e caminho de cache adaptados ao package `com.crf1.godhand`.
- Flags de compilação ajustadas para evitar exigir opcionalmente dot-product, FP16 e RCpc, mantendo a base ARMv8.2 para ampliar a compatibilidade com dispositivos ARM64 mais antigos.

## Dependências e fontes fixadas

O workflow usa estes commits para manter o build reproduzível:

- GodHandAndroidPort: `5f14ee6a84eb0213372c054c690e6a58513c1a17`
- SIMDe: `2e5224981e9123b8fe89a9833a978556d636a565`
- Oboe: `4468344c8fdfdd7df068d5abefe2d8615fd6b431`

O workflow faz sparse checkout do subdiretório C++ inteiro da referência, sem copiar esses arquivos gerados para o histórico deste repositório. O CMake integra a árvore da referência como um único conjunto para evitar misturar a implementação gráfica/IOP dela com uma segunda cópia do runtime.

## Como testar

1. Abra **Actions → Android ARM64 → Run workflow** e inicie o workflow manualmente.
2. Baixe o artefato `GodHand-CRF1-Android-ARM64-debug` e instale o APK em um dispositivo Android ARM64.
3. Selecione sua própria ISO de God Hand USA (serial SLUS-21503).
4. Toque em **Jogar**. O aplicativo deve validar a ISO, extrair os arquivos e abrir a atividade gráfica nativa.
5. Se aparecer tela preta, fechamento inesperado ou mensagem de erro, exporte o log do launcher. As mensagens nativas adicionais podem ser consultadas no Logcat com a tag `GodHandRecomp`.

A extração copia os dados necessários para o armazenamento privado. Reserve **mais de 1,6 GB de espaço livre**, além do espaço ocupado pela ISO, para que a extração não falhe por falta de armazenamento.

## Configuração gráfica inicial

A atividade de jogo inicia com Vulkan, escala interna de 0,75x, tela cheia e limite de 30 FPS para privilegiar compatibilidade no primeiro teste. Essas opções são valores iniciais fixos da integração e ainda não foram validadas no dispositivo do usuário. Uma tela preta não prova, sozinha, que a extração falhou; consulte os logs.

## Workflow e validação

O arquivo `.github/workflows/android.yml` é **somente manual** (`workflow_dispatch`). Commits e pushes não devem iniciar builds automaticamente. Nenhum workflow deve ser iniciado ou cancelado pelo assistente.

Uma compilação bem-sucedida confirma que o código nativo foi compilado e empacotado; não prova que o boot, o renderizador, o áudio, os overlays ou a jogabilidade funcionem. Essas etapas dependem de teste real no dispositivo.

## ISO e licenças

Este repositório não distribui ISO, ELF, arquivos de jogo ou outros dados protegidos. Use somente sua própria cópia legítima da versão USA exigida pelo projeto. O código de recompilação/runtime é GPL-3.0 e as dependências de terceiros têm suas próprias licenças; consulte os avisos correspondentes antes de redistribuir builds modificadas.
