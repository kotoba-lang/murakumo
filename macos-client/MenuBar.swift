import SwiftUI
import AppKit
struct NativeStatus: Decodable { let host:String; let backend:String; let engineReady:Bool; let residentBytes:Int; let virtualMachine:Bool }
@MainActor class StatusModel: ObservableObject {
 @Published var text="接続を確認しています…"
 func refresh() {
  do {
   let root=FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("github/workspaces/murakumo/native-client")
   let data=try Data(contentsOf:root.appendingPathComponent("config.json"))
   let c=try JSONSerialization.jsonObject(with:data) as! [String:Any]
   let token=try String(contentsOfFile:c["tokenFile"] as! String,encoding:.utf8).trimmingCharacters(in:.whitespacesAndNewlines)
   var request=URLRequest(url:URL(string:"http://127.0.0.1:\(c["port"]!)/status")!)
   request.setValue("Bearer "+token,forHTTPHeaderField:"Authorization");request.timeoutInterval=8
   URLSession.shared.dataTask(with:request){ data,_,_ in
    guard let data=data, let status=try? JSONDecoder().decode(NativeStatus.self,from:data) else {Task{@MainActor in self.text="Clientに接続できません"};return}
    Task{@MainActor in self.text="\(status.host)\n\(status.backend)\n推論エンジン: \(status.engineReady ? "利用可能" : "準備中")\nClient: \(status.residentBytes/1048576) MiB\nmacOS ネイティブ"}
   }.resume()
  }catch{text="Clientを先にインストールしてください"}
 }
}
@main struct MurakumoClientApp: App {
 @StateObject var model=StatusModel()
 var body: some Scene { MenuBarExtra("Murakumo",systemImage:"cloud") {Text(model.text).onAppear{model.refresh()};Button("状態を更新"){model.refresh()};Divider();Button("終了"){NSApplication.shared.terminate(nil)}} }
}
