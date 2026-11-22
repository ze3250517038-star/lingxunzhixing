<script setup>
import { computed, ref } from 'vue'
import { diffLines } from 'diff'

const props = defineProps({
  previous: { type: Object, required: true },
  current: { type: Object, required: true }
})
const opened = ref(false)
const changesOnly = ref(true)

const blocks = computed(() => {
  if (!opened.value) return []
  return diffLines(props.previous.snapshot, props.current.snapshot, {
    ignoreNewlineAtEof: true,
    timeout: 150,
    maxEditLength: 4000
  })?.map((change, index) => ({
    key: index,
    kind: change.added ? 'new' : change.removed ? 'old' : 'shared',
    text: change.value.endsWith('\n') ? change.value.slice(0, -1) : change.value
  })) ?? null
})
const visibleBlocks = computed(() => blocks.value?.filter(block => !changesOnly.value || block.kind !== 'shared'))
const labels = { old: '旧版独有', new: '新版独有', shared: '两版共有' }
</script>

<template>
  <details class="archive notice-diff" @toggle="opened = $event.target.open">
    <summary>原文逐行对照</summary>
    <div v-if="opened" class="diff-content">
      <div class="panel-head">
        <strong>V{{ previous.number }} 与 V{{ current.number }} 的归档正文</strong>
        <label v-if="blocks" class="diff-toggle"><input v-model="changesOnly" type="checkbox">只看差异</label>
      </div>
      <p class="quiet">旧版独有的段落仍可作为继承依据，以字段核验结果为准。</p>
      <div v-if="blocks === null" class="comparison diff-fallback">
        <div><span class="label">V{{ previous.number }} 原文</span><pre>{{ previous.snapshot }}</pre></div>
        <div><span class="label">V{{ current.number }} 原文</span><pre>{{ current.snapshot }}</pre></div>
      </div>
      <p v-else-if="!visibleBlocks.length" class="quiet diff-empty">两版正文逐行一致。</p>
      <ul v-else class="diff-list" aria-label="通知原文差异">
        <li v-for="block in visibleBlocks" :key="block.key" :data-kind="block.kind">
          <strong>{{ labels[block.kind] }}</strong><pre>{{ block.text || '（空行）' }}</pre>
        </li>
      </ul>
    </div>
  </details>
</template>
