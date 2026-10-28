# GodHand-CRF1-RECOMP

Projeto experimental de port do [God Hand Recompiled oficial](https://github.com/LasVola/godhand-recomp) para Android ARM64.

## Estado atual

O projeto avançou além do launcher de diagnóstico:

- Launcher Android em Kotlin com seleção de ISO via Storage Access Framework e permissão persistente de leitura.
- Log persistente em armazenamento privado, Logcat e exportação do diagnóstico.
- Biblioteca JNI libgodhand_android.so ligada ao núcleo ps2_runtime do repositório oficial.
- Build Android usa o NDK e a camada SSE2NEON para adaptar intrínsecos SSE do runtime à arquitetura ARM64.
- Ao tocar em **Jogar**, o código nativo lê a ISO9660 pelo descritor de arquivo, verifica o descritor primário, o diretório raiz, SYSTEM.CNF e SLUS_215.03, e informa se o disco parece ser a versão esperada.
- O runtime oficial é fixado no commit 14e956a1274332440273845fbab2c1f1e30c4a5a para builds reproduzíveis.

**Ainda não é uma versão jogável.** O núcleo genérico PS2 está ligado, mas ainda falta gerar e ligar ao APK o código C++ específico de God Hand, integrar o caminho de boot e validar o backend gráfico e os recursos extraídos necessários. O projeto oficial é uma recompilação estática: escolher uma ISO no Android não gera automaticamente esse código e não inicia o jogo. O estado atual não deve ser confundido com a integração completa ou com um boot bem-sucedido.

## Referências técnicas

- Projeto oficial God Hand Recompiled: https://github.com/LasVola/godhand-recomp
- CMake principal oficial: https://github.com/LasVola/godhand-recomp/blob/14e956a1274332440273845fbab2c1f1e30c4a5a/CMakeLists.txt
- Runtime PS2 oficial: https://github.com/LasVola/godhand-recomp/tree/14e956a1274332440273845fbab2c1f1e30c4a5a/runtime
- CMake do runtime oficial: https://github.com/LasVola/godhand-recomp/blob/14e956a1274332440273845fbab2c1f1e30c4a5a/runtime/ps2xRuntime/CMakeLists.txt
- SSE2NEON: https://github.com/DLTcollab/sse2neon

O CMake Android importa somente o subdiretório runtime/ do projeto oficial, e não o projeto desktop completo, pois este exige arquivos C++ gerados específicos do jogo que ainda não estão presentes neste repositório.

## Integração do código recompilado

Você tinha razão em apontar o repositório de referência: **ele contém o código C++ recompilado do executável principal**, não apenas os wrappers Unity. A árvore pública no commit `5f14ee6a84eb0213372c054c690e6a58513c1a17` contém cerca de 10.620 arquivos `.cpp` do executável principal, o arquivo de tabela `register_functions.cpp` (aprox. 12,7 MB) e 64 lotes Unity de funções mais o lote da tabela. Eu havia tratado a ausência de fontes na nossa árvore como se elas não estivessem disponíveis na referência; essa conclusão estava incompleta.

A integração agora foi preparada assim:

- O workflow manual busca a referência em um **commit fixo** e usa sparse checkout apenas de `generated/`, `generated_unity/` e `simd_arm64_compat.h`. Isso evita copiar mais de 180 MB de código gerado para o histórico deste repositório.
- O CMake prioriza esses arquivos reais: compila os wrappers Unity e a tabela de despacho, em vez da tabela vazia do runtime.
- Se a referência não for baixada, o CMake mantém o caminho local/fallback e não finge que o código do jogo foi ligado.
- O workflow continua sendo **manual**; não iniciei compilação nem executei Run workflow.

**Limite que ainda precisa ser validado:** essa mudança liga o código recompilado do ELF principal ao runtime que já usamos. A referência também possui milhares de funções dos overlays SNR2, mas eles dependem de um registro de módulos e de alterações no runtime Android daquela referência. Não os incluí cegamente no runtime LasVola, porque isso poderia gerar uma APK que compila mas falha quando o jogo carrega salas/eventos. A integração dos overlays e a validação real de boot/gráficos ainda não podem ser declaradas concluídas até testar a compatibilidade.

Fonte técnica usada: [GodHandAndroidPort](https://github.com/owaissseeee/GodHandAndroidPort/tree/5f14ee6a84eb0213372c054c690e6a58513c1a17/app/src/main/cpp), em particular `generated/`, `generated_unity/` e `host_tools/build_recomp_sources.py`. O projeto de referência declara GPL-3.0; confira os avisos/licenças de terceiros antes de redistribuir uma APK.

## Execução manual do workflow

O workflow `.github/workflows/android.yml` está configurado **somente para `workflow_dispatch`**. Pushes e commits não devem iniciar compilação automaticamente. Quando quiser testar, abra **Actions → Android ARM64 → Run workflow** e inicie a execução manualmente.

## ISO e direitos autorais

Use somente sua própria cópia legítima da versão USA exigida pelo projeto upstream (God Hand, serial SLUS-21503). Este repositório não inclui ISO, ELF, código gerado, overlays, músicas ou outros dados do jogo.

O núcleo de runtime upstream é licenciado sob GPL-3.0. Consulte a licença e os avisos de terceiros antes de distribuir builds modificadas.

## Build

Quando disparado manualmente, o workflow Android ARM64 compila o APK debug com Java, Android SDK, NDK e CMake, verifica a biblioteca ARM64 e publica o artefato GodHand-CRF1-Android-ARM64-debug. A compilação confirma apenas que o código incluído foi compilado; não prova que o jogo já inicializa.
