# GodHand-CRF1-RECOMP

Projeto experimental para investigar a adaptação do [God Hand Recompiled](https://github.com/LasVola/godhand-recomp) para Android ARM64.

## Estado atual — launcher e diagnóstico

- Launcher Android em Kotlin com seleção de ISO pelo seletor de documentos do sistema.
- A URI da ISO é persistida com a permissão concedida pelo Android; o app não copia a ISO inteira para o armazenamento interno.
- O botão **Jogar** verifica se a ISO selecionada continua acessível e registra cada etapa.
- Log persistente em armazenamento privado do app, Logcat e botão para compartilhar/exportar o texto do diagnóstico.
- A biblioteca JNI é carregada somente depois de o usuário selecionar uma ISO e tocar em **Jogar**.
- O workflow GitHub Actions compila um APK de diagnóstico e verifica a biblioteca `arm64-v8a`.

**Importante:** este commit ainda não é um port jogável. A checagem nativa retorna explicitamente `RUNTIME_NOT_INTEGRATED`. O runtime upstream usa componentes de desktop/Windows e depende de código recompilado específico do executável do jogo; ainda é necessário portar e integrar o runtime PS2 (GS, IOP, áudio, entradas, overlays e código gerado) para Android. Não seria correto alegar que isso está pronto apenas por criar um seletor de ISO ou uma biblioteca JNI.

## ISO e direitos autorais

Use somente sua própria cópia legítima da versão USA exigida pelo projeto upstream (SLUS-21503). O repositório não inclui ISO, ELF, overlays, assets nem outros dados do jogo.

## Build

O workflow `Android ARM64` instala Java/Android SDK/NDK/CMake, compila o APK debug e publica o artefato `GodHand-CRF1-Android-ARM64-debug`. A presença da biblioteca nativa confirma apenas a cadeia de build, não a compatibilidade do jogo.
