import type { Icon } from "@phosphor-icons/react";
import {
  Books,
  ChartBar,
  ChatCircleText,
  MagnifyingGlass,
  PlugsConnected,
  ShieldCheck,
  SquaresFour,
  Stack,
  Users,
} from "@phosphor-icons/react";
export type Page =
  | "quick-answer"
  | "evaluation"
  | "improvements"
  | "operations"
  | "overview"
  | "knowledge"
  | "tasks"
  | "search"
  | "answers"
  | "apps"
  | "usage"
  | "members"
  | "audit"
  | "models"
  | "settings";
export const nav: { id: Page; label: string; icon: Icon; group: string }[] = [
  { id: "overview", label: "工作台", icon: SquaresFour, group: "工作空间" },
  { id: "knowledge", label: "知识库", icon: Books, group: "工作空间" },
  { id: "tasks", label: "任务中心", icon: Stack, group: "工作空间" },
  { id: "answers", label: "知识库问答", icon: ChatCircleText, group: "知识应用" },
  { id: "apps", label: "应用中心", icon: PlugsConnected, group: "知识应用" },
  { id: "improvements", label: "知识改进", icon: Stack, group: "知识应用" },
  { id: "search", label: "检索调试", icon: MagnifyingGlass, group: "知识应用" },
  {
    id: "evaluation",
    label: "测试集与评审",
    icon: ChartBar,
    group: "知识应用",
  },
  { id: "operations", label: "运行状态", icon: ChartBar, group: "企业管理" },
  { id: "usage", label: "套餐与用量", icon: ChartBar, group: "企业管理" },
  { id: "members", label: "成员与权限", icon: Users, group: "企业管理" },
  { id: "models", label: "模型配置", icon: PlugsConnected, group: "企业管理" },
  { id: "audit", label: "审计日志", icon: ShieldCheck, group: "企业管理" },
  { id: "settings", label: "企业设置", icon: ShieldCheck, group: "企业管理" },
];
