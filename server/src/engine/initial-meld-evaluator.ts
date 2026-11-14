import { BoardState, GameConfig, MeldSet, PlayerRack, Tile } from '../models/types.js';
import { BoardValidator } from './board-validator.js';

export interface InitialMeldResult {
  isValid: boolean;
  score: number;
  requiredThreshold: number;
  newlyPlacedMeldsCount: number;
  newlyPlacedTiles: Tile[];
  failureReason?: string;
}

export class InitialMeldEvaluator {
  static evaluate(
    initialBoardState: BoardState,
    proposedBoardState: BoardState,
    initialRack: PlayerRack,
    config: GameConfig
  ): InitialMeldResult {
    const threshold = config.initialMeldThreshold;

    const initialMeldIds = new Set(initialBoardState.melds.map(m => m.id));
    const proposedMelds = proposedBoardState.melds;

    // Rule: Cannot tamper with existing board melds
    for (const initialMeld of initialBoardState.melds) {
      const match = proposedMelds.find(m => m.id === initialMeld.id);
      if (!match || JSON.stringify(match.tiles) !== JSON.stringify(initialMeld.tiles)) {
        return {
          isValid: false,
          score: 0,
          requiredThreshold: threshold,
          newlyPlacedMeldsCount: 0,
          newlyPlacedTiles: [],
          failureReason: "Interdiction de manipuler ou modifier les combinaisons existantes sur le plateau lors de la première pose."
        };
      }
    }

    // Extract newly placed melds
    const newMelds = proposedMelds.filter(m => !initialMeldIds.has(m.id));
    if (newMelds.length === 0) {
      return {
        isValid: false,
        score: 0,
        requiredThreshold: threshold,
        newlyPlacedMeldsCount: 0,
        newlyPlacedTiles: [],
        failureReason: "Aucune nouvelle combinaison n'a été déposée depuis votre chevalet."
      };
    }

    // Verify all tiles are from player's starting rack
    const newlyPlacedTiles = newMelds.flatMap(m => m.tiles);
    const playerStartingTileIds = new Set(initialRack.tiles.map(t => t.id));

    const illegalTiles = newlyPlacedTiles.filter(t => !playerStartingTileIds.has(t.id));
    if (illegalTiles.length > 0) {
      return {
        isValid: false,
        score: 0,
        requiredThreshold: threshold,
        newlyPlacedMeldsCount: 0,
        newlyPlacedTiles: [],
        failureReason: "Des tuiles ne provenant pas de votre chevalet ont été utilisées."
      };
    }

    // Validate new melds and sum points
    let totalScore = 0;
    for (const meld of newMelds) {
      const validation = BoardValidator.validateMeld(meld, config.jokerPointStrategy);
      if (!validation.isValid) {
        return {
          isValid: false,
          score: 0,
          requiredThreshold: threshold,
          newlyPlacedMeldsCount: 0,
          newlyPlacedTiles: [],
          failureReason: `Combinaison invalide : ${validation.errorMessage}`
        };
      }

      if (meld.tiles.some(t => t.isJoker) && !config.jokerAllowedInInitialMeld) {
        return {
          isValid: false,
          score: 0,
          requiredThreshold: threshold,
          newlyPlacedMeldsCount: 0,
          newlyPlacedTiles: [],
          failureReason: "Les Jokers sont interdits lors de la première pose selon les règles de cette partie."
        };
      }

      totalScore += validation.points;
    }

    if (totalScore < threshold) {
      return {
        isValid: false,
        score: totalScore,
        requiredThreshold: threshold,
        newlyPlacedMeldsCount: newMelds.length,
        newlyPlacedTiles,
        failureReason: `Score insuffisant : ${totalScore} points obtenus sur les ${threshold} points requis pour la première pose.`
      };
    }

    return {
      isValid: true,
      score: totalScore,
      requiredThreshold: threshold,
      newlyPlacedMeldsCount: newMelds.length,
      newlyPlacedTiles
    };
  }
}
