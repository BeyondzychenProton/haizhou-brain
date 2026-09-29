<template>
  <AppLayout>
    <div class="page-head">
      <div>
        <h2>管理中心</h2>
        <p class="muted">能力启停、MCP 连接审核、员工定义编排与发布、平台账号管理</p>
      </div>
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="能力目录" name="capabilities">
        <CapabilityPanel v-if="tab === 'capabilities'" />
      </el-tab-pane>
      <el-tab-pane label="工具授权" name="grants">
        <ToolGrantPanel v-if="tab === 'grants'" />
      </el-tab-pane>
      <el-tab-pane label="MCP 工具" name="mcp">
        <McpPanel v-if="tab === 'mcp'" />
      </el-tab-pane>
      <el-tab-pane label="员工定义编排" name="definitions">
        <DefinitionPanel v-if="tab === 'definitions'" />
      </el-tab-pane>
      <el-tab-pane label="用户管理" name="users">
        <UserPanel v-if="tab === 'users'" />
      </el-tab-pane>
    </el-tabs>
  </AppLayout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AppLayout from '../../layouts/AppLayout.vue'
import CapabilityPanel from './CapabilityPanel.vue'
import ToolGrantPanel from './ToolGrantPanel.vue'
import McpPanel from './McpPanel.vue'
import DefinitionPanel from './DefinitionPanel.vue'
import UserPanel from './UserPanel.vue'

// 用 v-if 强制每次切换都重新挂载，保证看到的是最新数据而不是缓存的旧列表。
const tab = ref('capabilities')
</script>
