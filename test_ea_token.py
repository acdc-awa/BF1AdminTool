import urllib.request
import urllib.error

# ==============================================================================
# EA Token 获取测试脚本 (Python 版)
# ==============================================================================
# 请在下方填入您最新的 remid 和 sid：
REMID = "TUU6emRhV3NzbFZWb29nSkR5ZEJkNndwVElGaGd5ZWFzM0htR0k3azRhdDowNTI0NjY0NDA.wKQUD1FkYK3BhJZc6i9xyCpPqHeRqx2NC5zlat3t"
SID = "U2QzelJSZ0Qza3RJdzFoVWFLWlVzeFBqMmI3b3FYWUdxNWRTWkg1NnJYd2o0Z2RHQUwwV3RRMW9VMGo5bA.YCwnhebMY93oiwKq__fniXDewpdKg8oJs50YE7VTEzU"

URL = "https://accounts.ea.com/connect/auth?client_id=ORIGIN_JS_SDK&response_type=token&redirect_uri=nucleus%3Arest&prompt=none&release_type=prod"
USER_AGENT = "okhttp/4.9.1"

# 继承 HTTPRedirectHandler 阻止自动重定向以模拟 App 端 followRedirects(false)
class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def send_request(cookie_value, test_name):
    print("==============================================")
    print(f"运行中: {test_name}")
    print(f"发送的 Cookie: {cookie_value}")
    print("----------------------------------------------")
    
    opener = urllib.request.build_opener(NoRedirectHandler)
    req = urllib.request.Request(URL)
    req.add_header("Cookie", cookie_value)
    req.add_header("User-Agent", USER_AGENT)
    
    try:
        with opener.open(req) as response:
            print(f"响应状态码: {response.getcode()}")
            print("响应头:")
            for key, val in response.headers.items():
                print(f"  {key}: {val}")
            
            body = response.read().decode('utf-8')
            print(f"\n响应体:\n{body}")
                
    except urllib.error.HTTPError as e:
        print(f"响应状态码 (HTTPError): {e.code}")
        print("响应头:")
        for key, val in e.headers.items():
            print(f"  {key}: {val}")
        body = e.read().decode('utf-8')
        print(f"\n响应体:\n{body}")
    except Exception as e:
        print(f"请求发生错误: {e}")
    print("==============================================\n")

if __name__ == "__main__":
    # 1. 仅测试 remid
    #send_request(f"remid={REMID}", "测试 1：仅传入 remid")
    
    # 2. 仅测试 sid
    send_request(f"sid={SID}", "测试 2：仅传入 sid")
    
    # 3. 同时测试 remid 和 sid
    #send_request(f"remid={REMID}; sid={SID}", "测试 3：同时传入 remid 和 sid")
