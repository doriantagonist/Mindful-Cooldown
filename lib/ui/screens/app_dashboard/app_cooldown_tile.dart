import 'package:fluentui_system_icons/fluentui_system_icons.dart';
import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:mindful/config/hero_tags.dart';
import 'package:mindful/core/enums/item_position.dart';
import 'package:mindful/core/extensions/ext_build_context.dart';
import 'package:mindful/core/extensions/ext_duration.dart';
import 'package:mindful/core/extensions/ext_widget.dart';
import 'package:mindful/core/services/method_channel_service.dart';
import 'package:mindful/models/app_info.dart';
import 'package:mindful/ui/common/default_list_tile.dart';
import 'package:mindful/ui/dialogs/timer_picker_dialog.dart';
import 'package:mindful/ui/transitions/default_hero.dart';

/// Lets the user lock an app for a while after using it for a set time,
/// for example 10 minutes of use followed by a 30 minute lock.
class AppCooldownTile extends StatefulWidget {
  const AppCooldownTile({
    super.key,
    required this.appInfo,
  });

  final AppInfo appInfo;

  @override
  State<AppCooldownTile> createState() => _AppCooldownTileState();
}

class _AppCooldownTileState extends State<AppCooldownTile> {
  int _useSec = 0;
  int _lockSec = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final all = await MethodChannelService.instance.getAppCooldowns();
    final config = all[widget.appInfo.packageName];
    if (!mounted) return;

    setState(() {
      _useSec = config?[0] ?? 0;
      _lockSec = config?[1] ?? 0;
    });
  }

  Future<void> _pick() async {
    final heroTag = HeroTags.appCooldownTileTag(widget.appInfo.packageName);

    final useSec = await showCooldownTimePicker(
      context: context,
      heroTag: heroTag,
      title: widget.appInfo.name,
      info:
          "How long can you use this app before a cooldown starts? Use reset to remove the cooldown.",
      initialTime: _useSec > 0 ? _useSec : 10 * 60,
    );
    if (useSec == null || !mounted) return;

    if (useSec == 0) {
      await MethodChannelService.instance
          .updateAppCooldown(widget.appInfo.packageName, 0, 0);
      await _load();
      return;
    }

    final lockSec = await showCooldownTimePicker(
      context: context,
      heroTag: heroTag,
      title: widget.appInfo.name,
      info:
          "How long should the app stay locked once the cooldown starts? The use time starts counting again when the lock ends.",
      initialTime: _lockSec > 0 ? _lockSec : 30 * 60,
    );
    if (lockSec == null || lockSec == 0 || !mounted) return;

    await MethodChannelService.instance
        .updateAppCooldown(widget.appInfo.packageName, useSec, lockSec);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final isSet = _useSec > 0 && _lockSec > 0;

    return DefaultHero(
      tag: HeroTags.appCooldownTileTag(widget.appInfo.packageName),
      child: DefaultListTile(
        enabled: !widget.appInfo.isImpSysApp,
        position: ItemPosition.mid,
        titleText: "App cooldown",
        subtitleText: isSet
            ? "Locked for ${_lockSec.seconds.toTimeFull(context)} after ${_useSec.seconds.toTimeFull(context)} of use"
            : context.locale.app_limit_status_not_set,
        leadingIcon: FluentIcons.timer_20_regular,
        onPressed: _pick,
      ),
    ).sliver;
  }
}
