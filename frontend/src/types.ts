export interface Room {
  id: number;
  name: string;
  location: string | null;
  capacity: number;
  description: string | null;
}

export interface Booking {
  id: number;
  roomId: number;
  roomName: string;
  title: string;
  organizerId: number | null;
  organizerName: string;
  organizerEmail: string | null;
  description: string | null;
  startTime: string;
  endTime: string;
  createdAt: string;
  updatedAt: string;
}

export interface BookingRequest {
  roomId: number;
  title: string;
  description: string | null;
  startTime: string;
  endTime: string;
}

export interface User {
  id: number;
  email: string;
  fullName: string;
}

export interface Session {
  accessToken: string;
  expiresAt: string;
  user: User;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
}

export type McpTransport = 'STREAMABLE_HTTP' | 'SSE';

export interface McpTool {
  name: string;
  description: string | null;
}

export interface McpServer {
  id: number;
  name: string;
  url: string;
  transport: McpTransport;
  enabled: boolean;
  headerName: string | null;
  headerValueMasked: string | null;
  forwardUserToken: boolean;
  status: 'CONNECTED' | 'ERROR' | 'DISCONNECTED' | 'DISABLED';
  error: string | null;
  serverInfo: string | null;
  tools: McpTool[];
  createdAt: string;
}

export interface McpServerRequest {
  name: string;
  url: string;
  transport: McpTransport;
  headerName: string | null;
  headerValue: string | null;
  forwardUserToken: boolean;
}

export interface McpTestResult {
  success: boolean;
  serverInfo: string | null;
  tools: McpTool[];
  error: string | null;
}
