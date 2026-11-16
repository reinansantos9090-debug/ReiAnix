# ReiAnix — auditoria HWUI e otimizações de renderização

## Objetivo
Reduzir quadros lentos na Home e na Biblioteca sem mudar a velocidade de aparição
das capas, identidade visual, ordenação dos cards, busca ou o gerenciamento de thumbnails.

## Alterações propostas e riscos controlados
- `libraryPresentationState` consome contagens computadas no Dispatcher.Default,
  em vez de percorrer os episódios novamente ao atualizar a UI.
- Estado observado pelo Compose root reduzido aos campos realmente usados
  (contagem de animes e status de scan); progresso e artwork não invalidam o root.
- Resumo de episódios imutável calculado uma vez por anime com cache thread-safe;
  cards da biblioteca agregam suas flags em uma passagem.
- Descrições de acessibilidade e metadados reutilizam textos memoizados.
- Callbacks de artwork registram diagnósticos em worker serial e sem perda de
  eventos; não fazem normalização pesada e cópias do histórico na UI thread.
- O poster mantém o `BoxWithConstraints` e dimensionamento exato do primeiro frame,
  evitando regressão por sizing com `onSizeChanged`. Cálculos independentes das
  dimensões saem do hot path de subcomposição. Nenhuma mudança no cache/extração
  de thumbnails, resolução, Coil cache key ou download de capas.
- Cabeçalho usa `Layout` simples preservando dimensões e gesto existente.
- Home remove subcomposição desnecessária do Hero e desenha os dois gradientes
  originais em um único elemento. Sem novo `graphicsLayer`, que elevaria o uso
  de memória GPU sem prova de benefício.
- Build Release ativa R8 (proguard-android-optimize) e resource shrinking, validado
  pelo pipeline de build. Nenhum perfil de baseline fictício foi adicionado.

## Restrições e verificação
Código-fonte estático não comprova 60/90/120 FPS constantes. Os vídeos HWUI mostram
picos; a atribuição da causa exige rastreamento de frames reais no dispositivo.

Para comparar versões **Release** instaladas no mesmo dispositivo, com a mesma
quantidade de animes e mesmo tamanho de cards:
```bash
adb shell dumpsys gfxinfo com.reiflix.reiflix_local reset
# Abra a Home e role até o final da Biblioteca da mesma forma em cada APK.
adb shell dumpsys gfxinfo com.reiflix.reiflix_local framestats > reianix-framestats.txt
```

As métricas desejadas são P50/P90/P95/P99 da duração dos frames,
porcentagem de frames fora do orçamento do refresh rate, e contagens de
frames superiores a 32/50/100 ms. Execute Perfetto/FrameTimeline e
Compose Layout Inspector para identificar composição, medida, desenho e
callbacks que ainda ocupem a UI thread.

Não misture resultados Debug, Release debug-signed e Release oficial sem
registrar a configuração, dispositivo, taxa de atualização, momento de
aquecimento do cache e densidade da grade. A certificação de CI não
representa medição HWUI no hardware físico.
