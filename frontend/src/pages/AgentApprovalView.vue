<script setup lang="ts">
import AgentApprovalQueue from '../components/AgentApprovalQueue.vue'
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <p class="eyebrow">WORKSPACE / APPROVALS</p>
        <h1>审批中心</h1>
        <p class="muted">处理你有权限的队列中的 Agent 建议。每次操作都会记录到审计日志。</p>
      </div>
    </div>
    <div class="panel">
      <AgentApprovalQueue />
    </div>
  </section>
</template>
