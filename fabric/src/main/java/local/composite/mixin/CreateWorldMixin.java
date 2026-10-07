package local.composite.mixin;
import local.composite.Games;
import local.composite.WorldCreator;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Adds a "Map" choice to Create New World: plain Minecraft, or a world from an installed game. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldMixin extends Screen {
 @Shadow @Final private WorldCreationUiState uiState;
 @Unique private int composite$game;
 @Unique private Button composite$button;
 protected CreateWorldMixin(){super(Component.empty());}
 @Inject(method="init",at=@At("TAIL"))
 private void composite$init(CallbackInfo ci){
  composite$button=Button.builder(Component.empty(),button->{
   // Step to the next game that is installed; the others stay listed in the tooltip.
   do composite$game=(composite$game+1)%Games.ALL.size();while(!Games.ALL.get(composite$game).available());
   composite$label();
  }).bounds(4,this.height-28,Math.clamp(this.width/2-162,70,150),20).build(); // left of the footer buttons
  composite$label();
  addRenderableWidget(composite$button);
 }
 @Unique private void composite$label(){
  composite$button.setMessage(Component.literal("Map: "+Games.ALL.get(composite$game).title()));
  var lines=new StringBuilder("World to create. Click to change.");
  for(var game:Games.ALL)lines.append("\n").append(game.available()?"✔ ":"✘ ").append(game.title()).append(game.available()?"":" — "+game.missing());
  composite$button.setTooltip(Tooltip.create(Component.literal(lines.toString())));
 }
 @Inject(method="onCreate",at=@At("HEAD"),cancellable=true)
 private void composite$create(CallbackInfo ci){
  var game=Games.ALL.get(composite$game);
  if(game==Games.MINECRAFT)return;
  ci.cancel();
  boolean creative=uiState.getGameMode()==WorldCreationUiState.SelectedGameMode.CREATIVE;
  WorldCreator.create(this.minecraft,this,game,uiState.getName().trim(),uiState.getTargetFolder(),creative?1:0,creative||uiState.isAllowCommands());
 }
}
