import asyncio
import websockets
import json

connected_clients = {}

def parse_message(data):
    try:
        json_data = json.loads(data)
        return json_data
    except:
        return None

async def send_to_client(client_id, data):
    if client_id in connected_clients:
        print("Send to", client_id)
        await connected_clients[client_id].send(data)

async def handle_client(websocket, path):
    client_id = path.strip('/')
    connected_clients[client_id] = websocket
    try:
        async for message in websocket:
            json_data = parse_message(message)
            if not json_data:
                continue
            await send_to_client(json_data["receiver"], message)
    finally:
        del connected_clients[client_id]
        await websocket.close()

async def main():
    async with websockets.serve(handle_client, "localhost", 8765):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
