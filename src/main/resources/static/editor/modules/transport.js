export class SessionTransport {
  constructor(sessionId, state) { this.sessionId = sessionId; this.state = state; }
  api(path) { return `/api/v1/editor/sessions/${this.sessionId}${path}`; }
  headers(values = {}) {
    if (!this.state.verified?.browserLeaseToken) throw new Error('The browser session is not authenticated.');
    return { Authorization: `Bearer ${this.state.verified.browserLeaseToken}`, ...values };
  }
  requireConnection() {
    if (!this.state.connected || this.state.socket?.readyState !== WebSocket.OPEN)
      throw new Error('The Persona server connection is unavailable.');
  }
}
