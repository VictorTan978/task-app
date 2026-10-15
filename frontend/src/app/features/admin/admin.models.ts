/** Models matching `contracts/openapi.yaml` (0.9.0) admin schemas exactly. */
import { Role } from '../../core/auth/auth.models';

/** One account as seen by an admin (no tasks, no password data). */
export interface AdminUser {
  id: number;
  email: string;
  displayName: string;
  /** ISO 8601 date-time, UTC. */
  createdAt: string;
  emailVerified: boolean;
  googleConnected: boolean;
  role: Role;
  disabled: boolean;
}

/** `GET /api/admin/users`: one page of accounts, newest first. */
export interface AdminUserPage {
  content: AdminUser[];
  /** Zero-based page index. */
  page: number;
  size: number;
  totalElements: number;
  /** 0 when nothing matches. */
  totalPages: number;
}

/** Roles that can be set through the API (`SUPER_ADMIN` never can). */
export type AssignableRole = Extract<Role, 'USER' | 'ADMIN'>;

/** Body of `PUT /api/admin/users/{id}/role`. */
export interface ChangeRoleRequest {
  role: AssignableRole;
}
