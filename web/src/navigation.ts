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
  | "overview"
  | "knowledge"
  | "tasks"
  | "search"
  | "answers"
  | "apps"
  | "usage"
  | "members"
  | "audit";
export const nav: { id: Page; label: string; icon: Icon; group: string }[] = [
  { id: "overview", label: "工作台", icon: SquaresFour, group: "工作空间" },
  { id: "knowledge", label: "知识库", icon: Books, group: "工作空间" },
  { id: "tasks", label: "任务中心", icon: Stack, group: "工作空间" },
  { id: "search", label: "检索调试", icon: MagnifyingGlass, group: "知识应用" },
  { id: "answers", label: "引用问答", icon: ChatCircleText, group: "知识应用" },
  { id: "apps", label: "应用中心", icon: PlugsConnected, group: "知识应用" },
  { id: "usage", label: "套餐与用量", icon: ChartBar, group: "企业管理" },
  { id: "members", label: "成员与权限", icon: Users, group: "企业管理" },
  { id: "audit", label: "审计日志", icon: ShieldCheck, group: "企业管理" },
];
