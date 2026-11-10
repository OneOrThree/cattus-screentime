import FamilyControls
import SwiftUI

/// FamilyActivityPicker in a navigation bar with Cancel and Done. Done stays disabled while
/// nothing is selected.
struct CattusActivityPicker: View {
  @State private var selection: FamilyActivitySelection
  private let onDone: (FamilyActivitySelection) -> Void
  private let onCancel: () -> Void

  init(
    initialSelection: FamilyActivitySelection,
    onDone: @escaping (FamilyActivitySelection) -> Void,
    onCancel: @escaping () -> Void
  ) {
    _selection = State(initialValue: initialSelection)
    self.onDone = onDone
    self.onCancel = onCancel
  }

  var body: some View {
    NavigationView {
      FamilyActivityPicker(selection: $selection)
        .navigationTitle("Choose apps")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
          ToolbarItem(placement: .cancellationAction) {
            Button("Cancel", action: onCancel)
          }
          ToolbarItem(placement: .confirmationAction) {
            Button("Done") { onDone(selection) }
              .disabled(CattusStorage.isEmpty(selection))
          }
        }
    }
  }
}
